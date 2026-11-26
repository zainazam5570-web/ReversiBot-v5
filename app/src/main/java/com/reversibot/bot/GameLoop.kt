package com.reversibot.bot

import com.reversibot.engine.BLACK
import com.reversibot.engine.Level
import com.reversibot.engine.Rules
import com.reversibot.engine.Searcher
import com.reversibot.engine.WHITE
import com.reversibot.vision.BoardDetector
import com.reversibot.vision.BoardFinder
import com.reversibot.vision.BoardGeometry
import com.reversibot.vision.PixelSource
import com.reversibot.vision.Reading
import java.util.Random

/**
 * Bot ka dimagh. Android se alag rakha gaya hai taake PC par test ho sake.
 *
 * Har ~100ms: screen padho -> agar chaal chalne ke hints nazar aayen aur board stable ho
 * -> hints se apna color pehchano -> engine se best move -> us square par tap.
 * autoPlay band ho to sirf dekhta aur sochta hai (tap nahi karta).
 *
 * "Turn pass" banner ke liye:
 *  1) Board ki yaaddasht: disc kabhi khali nahi hota. Agar pichle board ke mukable mein koi disc
 *     gayab ho jaye (ya bohot saare naye discs aa jayen) to samjho banner/pop-up board ke upar hai:
 *     us frame ko ignore karo, yaad kiya hua board rakho, tap/think mat karo.
 *  2) Banner hatte hi foran dobara padho (lambi settle wait ke baghair).
 *  3) Apni chaal ke baad engine khud dekhta hai ke opponent ke paas chaal hai ya nahi. Nahi hai
 *     to pass pehle se pata hai: agli chaal usi waqt soch li jati hai, banner hatne par foran tap.
 *  4) Opponent ki chaal ke baad board agar "mera board + ek legal chaal" se exact match kare
 *     to animation khatam samjho aur settle ka intezar na karo.
 */
class GameLoop(
    private val frames: () -> PixelSource?,
    /** Manual calibration (sirf fallback, agar auto-detect board na dhoond sake). */
    private val geometry: () -> BoardGeometry?,
    private val tap: (Float, Float) -> Boolean,
    private val level: () -> Level,
    private val listener: Listener,
    private val pollMs: Long = 100,
    private val stableMs: Long = 350,
    /** Jab board ka agla halat pehle se pata ho (prediction match / banner abhi hata) to itna intezar. */
    private val fastStableMs: Long = 120,
    private val retryMs: Long = 2500,
    /** Banner is se zyada der "board gayab" rahe to yaaddasht bhool kar dobara sync karo. */
    private val bannerMaxMs: Long = 5000
) {
    interface Listener {
        fun onStatus(text: String)
        fun onFrame(reading: Reading?, myColor: Int, bestMove: Int)
    }

    @Volatile var autoPlay = false
    @Volatile private var running = false
    private var thread: Thread? = null

    private val detector = BoardDetector()
    private val finder = BoardFinder(detector)

    /** Jo geometry abhi istemal ho rahi hai (auto ya manual). */
    @Volatile var activeGeometry: BoardGeometry? = null; private set
    private var autoGeo: BoardGeometry? = null
    private var lastFindAt = 0L
    private var badSince = 0L
    private var srcW = 0
    private var srcH = 0
    private val searcher = Searcher()
    private val rnd = Random()

    private var lastSig = ""
    private var sigSince = 0L
    private var lastThinkKey = ""
    private var lastBest = -1
    private var lastColor = 0
    private var lastActKey = ""
    private var lastActAt = 0L

    // ---- board yaaddasht + banner ----
    private var mem: IntArray? = null      // aakhri "saaf" (consistent) board
    private var bannerSince = 0L           // > 0: banner/occlusion shak
    private var bannerEndedAt = 0L

    // ---- prediction (apni chaal ke baad ka board) ----
    private var predBoard: IntArray? = null
    private var predColor = 0
    private var ponderKey = ""             // pass ki surat mein pehle se soche hue board ki key
    private var ponderColor = 0
    private var ponderMove = -1

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "reversi-bot-loop").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        searcher.cancel()
        thread?.interrupt()
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { /* stop() */ }
    }

    private fun loop() {
        while (running) {
            try {
                step()
            } catch (e: Exception) {
                listener.onStatus("Error: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
            sleep(pollMs)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun boardKey(b: IntArray): String {
        val sb = StringBuilder(64)
        for (v in b) sb.append(when (v) { 1 -> 'B'; -1 -> 'W'; else -> '.' })
        return sb.toString()
    }

    private fun nearDisc(b: IntArray, i: Int): Boolean {
        val r = i shr 3
        val c = i and 7
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val rr = r + dr
            val cc = c + dc
            if (rr in 0..7 && cc in 0..7 && b[rr * 8 + cc] != 0) return true
        }
        return false
    }

    /**
     * Kya raw board, pichle saaf board se "mumkin agla halat" hai?
     * Reversi mein disc kabhi khali nahi hota, naye discs purane discs ke bagal mein aate hain
     * aur thode hi aate hain. Warna banner/pop-up ya ghalat read.
     */
    private fun consistent(prev: IntArray, raw: IntArray): Boolean {
        var prevN = 0
        var rawN = 0
        for (i in 0 until 64) {
            if (prev[i] != 0) { prevN++; if (raw[i] == 0) return false }
            if (raw[i] != 0) rawN++
        }
        if (rawN - prevN > 6) return false
        for (i in 0 until 64) if (prev[i] == 0 && raw[i] != 0 && !nearDisc(prev, i)) return false
        return true
    }

    private fun resetMemory() {
        mem = null
        bannerSince = 0L
        predBoard = null
        ponderKey = ""
        ponderMove = -1
        lastThinkKey = ""
        lastActKey = ""
        lastBest = -1
    }

    /**
     * Board pehle se pata hone wale halat se match karta hai? Tab animation/settle ka lamba
     * intezar nahi karte.
     */
    private fun settledByPrediction(board: IntArray): Boolean {
        val p = predBoard ?: return false
        // opponent ka pass pehle se pata tha: banner (apni chaal ke baad) aa kar hat chuka ho
        if (ponderKey.isNotEmpty() && boardKey(board) == ponderKey) return bannerEndedAt > lastActAt
        if (board.contentEquals(p)) return false
        // opponent ki ek legal chaal ke baad ka exact board
        for (m in Rules.moves(p, -predColor)) {
            if (Rules.play(p, m, -predColor).contentEquals(board)) return true
        }
        return false
    }

    // ------------------------------------------------------------------ main step

    private fun step() {
        val src = frames()
        if (src == null) {
            listener.onStatus("Screen capture ka intezar...")
            return
        }
        // screen size badli (rotate) to board dobara dhoondo
        if (src.width != srcW || src.height != srcH) {
            srcW = src.width; srcH = src.height
            autoGeo = null; lastFindAt = 0L; badSince = 0L
            resetMemory()
        }
        val t0 = System.currentTimeMillis()
        if (autoGeo == null && t0 - lastFindAt >= 700) {
            lastFindAt = t0
            autoGeo = finder.find(src)
            badSince = 0L
        }
        val usingAuto = autoGeo != null
        val geo = autoGeo ?: geometry()
        activeGeometry = geo
        if (geo == null) {
            listener.onStatus("Board nahi mila - game ki board wali screen kholein (ya 📐 Manual)")
            listener.onFrame(null, 0, -1)
            return
        }
        if (geo.right > src.width + 2 || geo.bottom > src.height + 2) {
            listener.onStatus("Screen size badal gaya (rotate?) - board dobara dhoond raha hun")
            autoGeo = null
            return
        }

        val reading = detector.read(src, geo)
        val now = System.currentTimeMillis()

        // ---- board yaaddasht / banner ----
        val prev = mem
        if (prev != null) {
            val rawN = reading.board.count { it != 0 }
            val prevN = prev.count { it != 0 }
            if (BoardFinder.isPlausible(reading) && rawN <= 5 && prevN >= rawN + 2) {
                resetMemory()                                    // naya game
            } else if (!consistent(prev, reading.board)) {
                if (bannerSince == 0L) bannerSince = now
                if (now - bannerSince <= bannerMaxMs) {
                    badSince = 0L
                    listener.onFrame(Reading(prev, BooleanArray(64)), lastColor, -1)
                    listener.onStatus(
                        if (autoPlay) "Pop-up/banner (Turn pass?) - board yaad hai, intezar..."
                        else "Pop-up/banner nazar aaya - board yaad hai"
                    )
                    return
                }
                resetMemory()                                    // bohot der ho gayi: dobara sync
            } else {
                if (bannerSince != 0L) { bannerEndedAt = now; bannerSince = 0L }
                mem = reading.board.copyOf()
            }
        }
        if (mem == null && BoardFinder.isPlausible(reading)) mem = reading.board.copyOf()

        val sig = reading.signature()
        if (sig != lastSig) { lastSig = sig; sigSince = now }

        // auto board ka check: beech ke 4 squares hamesha discs hote hain. 1.5s tak na hon
        // to board hat gaya (menu/naya screen) - dobara dhoondo.
        if (usingAuto) {
            if (BoardFinder.isPlausible(reading)) badSince = 0L
            else if (badSince == 0L) badSince = now
            else if (now - badSince > 1500) {
                autoGeo = null; badSince = 0L; lastFindAt = 0L
                listener.onStatus("Board hat gaya - dobara dhoond raha hun...")
                return
            }
        }

        if (reading.hintCount == 0) {
            listener.onFrame(reading, lastColor, -1)
            listener.onStatus(if (autoPlay) "Opponent ki baari / intezar..." else "Dekh raha hun (Auto band)")
            return
        }
        val predicted = settledByPrediction(reading.board)
        val needStable = if (predicted) fastStableMs else stableMs
        if (now - sigSince < needStable) {
            listener.onFrame(reading, lastColor, -1)
            listener.onStatus("Board settle ho raha hai...")
            return
        }
        // Hints (peele glow) pulse karte hain, kabhi kuch hints nazar nahi aate. Sirf tab sochte hain jab
        // hints bilkul poore legal moves ke barabar hon (warna aadhe hints par ghalat soch).
        // Ek istisna: pass pehle se pata ho aur board wahi ho jo pehle se socha tha - tab
        // nazar aane wale hints legal hon to kaafi hai (banner/pulse se kuch hints chhup sakte hain).
        val hs = reading.hintSquares()
        val legalB = Rules.moves(reading.board, BLACK)
        val legalW = Rules.moves(reading.board, WHITE)
        fun exact(l: IntArray) = l.isNotEmpty() && l.size == hs.size && hs.all { it in l }
        val okB = exact(legalB)
        val okW = exact(legalW)
        val key = boardKey(reading.board)
        val ponderHit = predicted && ponderKey.isNotEmpty() && key == ponderKey &&
            hs.isNotEmpty() && hs.all { it in Rules.moves(reading.board, ponderColor) }
        val color: Int? = when {
            ponderHit -> ponderColor
            okB && okW -> if (lastColor != 0) lastColor else BoardDetector.inferColor(reading)
            okB -> BLACK
            okW -> WHITE
            else -> null
        }
        if (color == null) {
            listener.onFrame(reading, lastColor, -1)
            val partial = hs.all { it in legalB } || hs.all { it in legalW }
            listener.onStatus(
                if (partial) "Hints poore nazar nahi aaye (glow) - intezar..."
                else "Hints board se match nahi hue - board detection check karein"
            )
            return
        }
        lastColor = color
        val colorName = if (color == BLACK) "Black" else "White"

        // naye board par sochna (ek position ek baar)
        val thinkKey = key + color
        if (thinkKey != lastThinkKey) {
            if (ponderHit && ponderMove >= 0) {
                // pass pehle se pata tha: chaal pehle hi soch li gayi thi
                lastThinkKey = thinkKey
                lastBest = ponderMove
                listener.onFrame(reading, color, lastBest)
                listener.onStatus("Main $colorName | best: ${Rules.sqName(lastBest)} (pehle se soch li thi)")
            } else {
                listener.onFrame(reading, color, -1)
                listener.onStatus("Main $colorName hun - soch raha hun...")
                val lv = level()
                // poore legal moves par search (HTML jaisa); sirf final (mukammal depth) ki chaal li jati hai
                val res = searcher.run(reading.board, color, lv.budgetMs, lv.maxDepth, null)
                if (!running) return
                lastThinkKey = thinkKey
                lastBest = res.move
                listener.onFrame(reading, color, lastBest)
                if (res.move >= 0) {
                    listener.onStatus("Main $colorName | best: ${Rules.sqName(res.move)} (depth ${res.depth})")
                }
            }
        }
        if (lastBest < 0 || !autoPlay) return
        if (thinkKey == lastActKey && now - lastActAt < retryMs) return

        // thinking ke dauran screen badal to nahi gayi (ya banner to nahi aa gaya)?
        val src2 = frames() ?: return
        val again = detector.read(src2, geo)
        if (boardKey(again.board) != key) return
        mem?.let { if (!consistent(it, again.board)) return }

        sleep(if (predicted) 40L + rnd.nextInt(80) else 80L + rnd.nextInt(120))
        val move = lastBest
        val x = geo.centerX(move and 7)
        val y = geo.centerY(move shr 3)
        val ok = tap(x, y)
        lastActKey = thinkKey
        lastActAt = System.currentTimeMillis()
        listener.onStatus(
            if (ok) "Main $colorName | chaal: ${Rules.sqName(move)} ✔"
            else "Tap nahi hua - Accessibility service on karein"
        )
        if (!ok) return

        // apni chaal ke baad ka board yaad rakho; opponent ke paas chaal na ho to pass pehle se pata hai
        val after = Rules.play(reading.board, move, color)
        predBoard = after
        predColor = color
        ponderKey = ""
        ponderMove = -1
        if (Rules.moves(after, -color).isEmpty() && Rules.moves(after, color).isNotEmpty()) {
            listener.onStatus("Opponent ke paas chaal nahi (pass) - agli chaal pehle se soch raha hun...")
            val lv = level()
            val res = searcher.run(after, color, lv.budgetMs, lv.maxDepth, null)
            if (running && res.move >= 0) {
                ponderKey = boardKey(after)
                ponderColor = color
                ponderMove = res.move
                listener.onStatus("Opponent pass hoga - agli chaal ${Rules.sqName(res.move)} tayyar")
            }
        }
    }
}
