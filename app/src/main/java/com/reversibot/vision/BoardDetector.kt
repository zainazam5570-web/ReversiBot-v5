package com.reversibot.vision

import com.reversibot.engine.BLACK
import com.reversibot.engine.Rules
import com.reversibot.engine.WHITE
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Screen ke pixels padhne ka interface (Android mein Image se, test mein BufferedImage se). */
interface PixelSource {
    val width: Int
    val height: Int
    /** ARGB */
    fun pixel(x: Int, y: Int): Int
}

/** Board ka screen par rectangle (pixels mein). Calibration se aata hai. */
data class BoardGeometry(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cellW: Float get() = (right - left) / 8f
    val cellH: Float get() = (bottom - top) / 8f
    fun centerX(col: Int): Float = left + (col + 0.5f) * cellW
    fun centerY(row: Int): Float = top + (row + 0.5f) * cellH
}

/** Ek frame se padha hua board: discs + jahan chaal chalna mumkin hai (hints). */
class Reading(val board: IntArray, val hints: BooleanArray) {
    val hintCount: Int = hints.count { it }
    fun hintSquares(): IntArray = (0 until 64).filter { hints[it] }.toIntArray()
    fun signature(): String {
        val sb = StringBuilder(128)
        for (i in 0 until 64) {
            sb.append(
                when (board[i]) { 1 -> 'B'; -1 -> 'W'; else -> if (hints[i]) '*' else '.' }
            )
        }
        return sb.toString()
    }
}

/** Thresholds. Agar kisi game ke colors ki wajah se detection ghalat ho to yahan tune karein. */
class DetectorConfig(
    /** Disc aur background ke darmiyan kam az kam itna farq (RGB distance). */
    val discDist: Float = 45f,
    /** Is se kam brightness = black disc. */
    val blackMaxLum: Float = 105f,
    /** Is se zyada brightness = white disc. */
    val whiteMinLum: Float = 165f,
    /** Disc ke andar 4 nuqton ke rang mein zyada se zyada farq. */
    val ringSpreadMax: Float = 70f,
    /** Khali square ke beech mein dot hint ka farq. */
    val dotDist: Float = 38f,
    /** Poora square highlight hone ka farq (hint as highlight). */
    val highlightDist: Float = 32f,
    /** Peele glow wale hint squares: cell ke itne hisse mein peela rang ho to hint. */
    val glowFrac: Float = 0.15f,
    /** true: purane dot/highlight hints bhi (glow na mile tab). Texture wale boards par ghalat hints deta hai. */
    val legacyHints: Boolean = false,
    /** Safed disc nila-safed (B > R + itna) ho to halke tile par bhi pehchana jaye. */
    val whiteTint: Float = 10f
)

private class Rgb(val r: Float, val g: Float, val b: Float) {
    fun lum(): Float = 0.299f * r + 0.587f * g + 0.114f * b
    fun dist(o: Rgb): Float {
        val dr = r - o.r; val dg = g - o.g; val db = b - o.b
        return sqrt(dr * dr + dg * dg + db * db)
    }
}

class BoardDetector(private val cfg: DetectorConfig = DetectorConfig()) {

    private class Cell(
        val bg: Rgb, val center: Rgb, val color: Int, // color: 1 black, -1 white, 0 disc nahi
        val centerDist: Float,
        val glow: Float          // cell mein peele glow pixels ka hissa (0..1)
    )

    private fun patch(src: PixelSource, cx: Float, cy: Float, rad: Float): Rgb {
        val x0 = (cx - rad).toInt().coerceIn(0, src.width - 1)
        val x1 = (cx + rad).toInt().coerceIn(0, src.width - 1)
        val y0 = (cy - rad).toInt().coerceIn(0, src.height - 1)
        val y1 = (cy + rad).toInt().coerceIn(0, src.height - 1)
        val step = max(1, (x1 - x0) / 4)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val p = src.pixel(x, y)
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
                n++
                x += step
            }
            y += step
        }
        if (n == 0) return Rgb(0f, 0f, 0f)
        return Rgb(sr.toFloat() / n, sg.toFloat() / n, sb.toFloat() / n)
    }

    /** Cell ke andar saturated peele (glow) pixels ka hissa. */
    private fun glowFraction(src: PixelSource, cx: Float, cy: Float, cw: Float, ch: Float): Float {
        var n = 0; var hit = 0
        for (iy in 0 until 12) for (ix in 0 until 12) {
            val x = (cx + ((ix + 0.5f) / 12f - 0.5f) * cw * 0.9f).toInt().coerceIn(0, src.width - 1)
            val y = (cy + ((iy + 0.5f) / 12f - 0.5f) * ch * 0.9f).toInt().coerceIn(0, src.height - 1)
            val p = src.pixel(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            n++
            if (r > 170 && g > 150 && r - b > 70 && g - b > 45) hit++
        }
        return hit.toFloat() / n
    }

    private fun avg(list: List<Rgb>): Rgb {
        var r = 0f; var g = 0f; var b = 0f
        for (c in list) { r += c.r; g += c.g; b += c.b }
        val n = list.size.toFloat()
        return Rgb(r / n, g / n, b / n)
    }

    private fun analyzeCell(src: PixelSource, cx: Float, cy: Float, cw: Float, ch: Float): Cell {
        val s = minOf(cw, ch)
        val pr = max(2f, s * 0.05f)
        // 1) background: cell ke chaaron kone (disc ke bahar)
        val o = s * 0.36f
        val bg = avg(
            listOf(
                patch(src, cx - o, cy - o, pr), patch(src, cx + o, cy - o, pr),
                patch(src, cx - o, cy + o, pr), patch(src, cx + o, cy + o, pr)
            )
        )
        // 2) disc ke andar 4 nuqte (hint dot ke bahar)
        val q = s * 0.24f
        val ring = listOf(
            patch(src, cx - q, cy, pr), patch(src, cx + q, cy, pr),
            patch(src, cx, cy - q, pr), patch(src, cx, cy + q, pr)
        )
        val ringMean = avg(ring)
        var spread = 0f
        for (p in ring) spread = max(spread, p.dist(ringMean))
        // 3) beech ka hissa
        val center = patch(src, cx, cy, max(2f, s * 0.06f))

        val glow = glowFraction(src, cx, cy, cw, ch)
        val discish = glow < cfg.glowFrac && ringMean.dist(bg) > cfg.discDist && spread < cfg.ringSpreadMax
        val lum = ringMean.lum()
        val tintedWhite = glow < cfg.glowFrac && lum > cfg.whiteMinLum &&
            ringMean.b - ringMean.r > cfg.whiteTint && ringMean.dist(bg) > 25f && spread < cfg.ringSpreadMax
        val color = when {
            discish && lum < cfg.blackMaxLum -> BLACK
            discish && lum > cfg.whiteMinLum -> WHITE
            tintedWhite -> WHITE
            else -> 0
        }
        return Cell(bg, center, color, center.dist(bg), glow)
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return s[s.size / 2]
    }

    fun read(src: PixelSource, g: BoardGeometry): Reading {
        val cw = g.cellW
        val ch = g.cellH
        val cells = Array(64) { i -> analyzeCell(src, g.centerX(i and 7), g.centerY(i shr 3), cw, ch) }
        val board = IntArray(64) { cells[it].color }

        // Board ka normal (bina highlight) rang: sab squares ka parity-wise median.
        // Discs wale squares kabhi highlight nahi hote, is liye reference stable rehta hai.
        val med = Array(2) { par ->
            val l = (0 until 64).filter { (((it shr 3) + (it and 7)) and 1) == par }.map { cells[it] }
            Rgb(median(l.map { it.bg.r }), median(l.map { it.bg.g }), median(l.map { it.bg.b }))
        }
        val hints = BooleanArray(64)
        val glowMode = (0 until 64).any { board[it] == 0 && cells[it].glow >= cfg.glowFrac }
        if (glowMode || !cfg.legacyHints) {
            for (i in 0 until 64) hints[i] = board[i] == 0 && cells[i].glow >= cfg.glowFrac
            return Reading(board, hints)
        }
        for (i in 0 until 64) {
            if (board[i] != 0) continue
            val c = cells[i]
            val par = ((i shr 3) + (i and 7)) and 1
            val ref = med[par]
            val dot = c.centerDist > cfg.dotDist
            val highlight = c.bg.dist(ref) > cfg.highlightDist
            hints[i] = dot || highlight
        }
        return Reading(board, hints)
    }

    companion object {
        /**
         * Hints se pata lagata hai ke bot kis color se khel raha hai.
         * Hints ko us color ke legal moves se match karta hai; barabar hon to disc-count se andaza.
         * @return BLACK / WHITE, ya null agar hints kisi color se match nahi hote.
         */
        fun inferColor(r: Reading): Int? {
            val h = r.hintSquares()
            if (h.isEmpty()) return null
            val lb = Rules.moves(r.board, BLACK)
            val lw = Rules.moves(r.board, WHITE)
            fun coverage(legal: IntArray): Double {
                if (legal.isEmpty()) return -1.0
                if (!h.all { it in legal }) return -1.0     // koi hint is color ke liye legal nahi
                return h.size.toDouble() / legal.size
            }
            val cb = coverage(lb)
            val cw = coverage(lw)
            val okB = cb >= 0.5
            val okW = cw >= 0.5
            return when {
                okB && !okW -> BLACK
                okW && !okB -> WHITE
                okB && okW -> {
                    if (abs(cb - cw) > 1e-9) (if (cb > cw) BLACK else WHITE)
                    else {
                        // standard parity: shuru mein 4 discs, har chaal ek disc barhati hai
                        val occupied = r.board.count { it != 0 }
                        if ((occupied - 4) % 2 == 0) BLACK else WHITE
                    }
                }
                else -> null
            }
        }
    }
}
