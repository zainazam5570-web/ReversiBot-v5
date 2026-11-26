package com.reversibot.engine

/*
 * Reversi Coach 2 (HTML) ki search + evaluation ka Kotlin port.
 * Strategy bilkul wahi hai: iterative deepening alpha-beta, transposition table,
 * killer moves, corner / X-square / mobility / frontier / potential-mobility evaluation.
 *
 * Board: IntArray(64), index = row * 8 + col (row 0 = upar).
 * Values: 0 = khali, 1 = BLACK, -1 = WHITE.
 */

const val BLACK = 1
const val WHITE = -1

enum class Level(val label: String, val budgetMs: Long, val maxDepth: Int) {
    FAST("Fast and Strong", 900, 8),
    VERY_STRONG("Very Strong", 1600, 10),
    MAX("Maximum Search", 2600, 12)
}

data class SearchResult(val move: Int, val depth: Int, val nodes: Long, val score: Double)

object Rules {
    private val DR = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
    private val DC = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)

    fun isLegal(b: IntArray, sq: Int, p: Int): Boolean {
        if (b[sq] != 0) return false
        val r0 = sq shr 3
        val c0 = sq and 7
        for (k in 0 until 8) {
            var r = r0 + DR[k]
            var c = c0 + DC[k]
            var n = 0
            while (r in 0..7 && c in 0..7 && b[r * 8 + c] == -p) { n++; r += DR[k]; c += DC[k] }
            if (n > 0 && r in 0..7 && c in 0..7 && b[r * 8 + c] == p) return true
        }
        return false
    }

    /** Legal moves, index ke ascending order mein. */
    fun moves(b: IntArray, p: Int): IntArray {
        val out = IntArray(64)
        var n = 0
        for (i in 0 until 64) if (isLegal(b, i, p)) out[n++] = i
        return out.copyOf(n)
    }

    fun play(b: IntArray, m: Int, p: Int): IntArray {
        val nb = b.copyOf()
        nb[m] = p
        val r0 = m shr 3
        val c0 = m and 7
        for (k in 0 until 8) {
            var r = r0 + DR[k]
            var c = c0 + DC[k]
            var n = 0
            while (r in 0..7 && c in 0..7 && nb[r * 8 + c] == -p) { n++; r += DR[k]; c += DC[k] }
            if (n > 0 && r in 0..7 && c in 0..7 && nb[r * 8 + c] == p) {
                var rr = r0 + DR[k]
                var cc = c0 + DC[k]
                while (rr != r || cc != c) { nb[rr * 8 + cc] = p; rr += DR[k]; cc += DC[k] }
            }
        }
        return nb
    }

    fun count(b: IntArray, p: Int): Int {
        var n = 0
        for (x in b) if (x == p) n++
        return n
    }

    fun sqName(sq: Int): String = "ABCDEFGH"[sq and 7].toString() + ((sq shr 3) + 1)
}

private class SearchTimeout : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

private class TTEntry(val depth: Int, val score: Double, val move: Int, val flag: Int)

class Searcher {
    companion object {
        private val DR = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        private val DC = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        private val W = intArrayOf(
            120, -25, 20, 5, 5, 20, -25, 120,
            -25, -45, -5, -5, -5, -5, -45, -25,
            20, -5, 15, 3, 3, 15, -5, 20,
            5, -5, 3, 3, 3, 3, -5, 5,
            5, -5, 3, 3, 3, 3, -5, 5,
            20, -5, 15, 3, 3, 15, -5, 20,
            -25, -45, -5, -5, -5, -5, -45, -25,
            120, -25, 20, 5, 5, 20, -25, 120
        )
        private val CORNERS = intArrayOf(0, 7, 56, 63)
        private val NEAR = arrayOf(
            intArrayOf(1, 8, 9), intArrayOf(6, 14, 15),
            intArrayOf(48, 49, 57), intArrayOf(54, 55, 62)
        )

        private val ZOB = LongArray(64 * 2).also {
            val rnd = java.util.SplittableRandom(0x5EED1234L)
            for (i in it.indices) it[i] = rnd.nextLong()
        }
        private const val ZSIDE = 0x1D2C3B4A5F6E7D8CL

        fun evalBoard(b: IntArray, p: Int): Double {
            var occupied = 0
            var score = 0.0
            for (i in 0 until 64) {
                if (b[i] != 0) {
                    occupied++
                    score += if (b[i] == p) W[i] else -W[i]
                }
            }
            for (k in 0 until 4) {
                val c = CORNERS[k]
                if (b[c] == p) score += 100
                if (b[c] == -p) score -= 100
                if (b[c] == 0) {
                    for (q in NEAR[k]) {
                        if (b[q] == p) score -= 34
                        if (b[q] == -p) score += 34
                    }
                }
            }
            val myM = Rules.moves(b, p).size
            val opM = Rules.moves(b, -p).size
            var frontMe = 0
            var frontOp = 0
            for (i in 0 until 64) {
                if (b[i] != 0) {
                    val r = i shr 3
                    val c = i and 7
                    var front = false
                    for (k in 0 until 8) {
                        val y = r + DR[k]
                        val x = c + DC[k]
                        if (y in 0..7 && x in 0..7 && b[y * 8 + x] == 0) { front = true; break }
                    }
                    if (front) { if (b[i] == p) frontMe++ else frontOp++ }
                }
            }
            val phase = occupied / 64.0
            val mobilityWeight = if (phase < 0.7) 11 else 5
            val frontierWeight = if (phase < 0.7) 7 else 3
            score += (myM - opM) * mobilityWeight + (frontOp - frontMe) * frontierWeight
            var potMe = 0
            var potOp = 0
            for (i in 0 until 64) {
                if (b[i] == 0) {
                    val r = i shr 3
                    val c = i and 7
                    var adjP = false
                    var adjO = false
                    for (k in 0 until 8) {
                        val y = r + DR[k]
                        val x = c + DC[k]
                        if (y in 0..7 && x in 0..7) {
                            if (b[y * 8 + x] == -p) adjP = true
                            if (b[y * 8 + x] == p) adjO = true
                        }
                    }
                    if (adjP) potMe++
                    if (adjO) potOp++
                }
            }
            score += (potMe - potOp) * 2
            val discDiff = Rules.count(b, p) - Rules.count(b, -p)
            score += discDiff * (if (phase > 0.82) 8.0 else if (phase > 0.65) 3.0 else 0.5)
            return score
        }

        private fun key(b: IntArray, p: Int): Long {
            var h = if (p == 1) ZSIDE else 0L
            for (i in 0 until 64) {
                val v = b[i]
                if (v == 1) h = h xor ZOB[i] else if (v == -1) h = h xor ZOB[64 + i]
            }
            return h
        }
    }

    @Volatile private var cancelled = false
    private var deadline = 0L
    private var nodes = 0L
    private val tt = HashMap<Long, TTEntry>()
    private var killers = Array(64) { intArrayOf(-1, -1) }

    fun cancel() { cancelled = true }

    private fun orderMoves(b: IntArray, p: Int, ms: IntArray, ttMove: Int, depth: Int): IntArray {
        val scored = ArrayList<Pair<Int, Int>>(ms.size)
        for (m in ms) {
            var s = 0
            if (m == ttMove) s += 100000
            if (m == 0 || m == 7 || m == 56 || m == 63) s += 50000
            val r = m shr 3
            val c = m and 7
            if ((r == 1 || r == 6) && (c == 1 || c == 6)) s -= 1200
            s += W[m] * 8
            val kl = killers[depth]
            if (kl[0] == m) s += 7000
            if (kl[1] == m) s += 5000
            val nb = Rules.play(b, m, p)
            s += Rules.moves(nb, -p).size * -12
            scored.add(Pair(m, s))
        }
        // stable sort (JS ki tarah): barabar score par asal order barqarar rehta hai
        return scored.sortedByDescending { it.second }.map { it.first }.toIntArray()
    }

    private fun search(b: IntArray, p: Int, depth: Int, alphaIn: Double, betaIn: Double): Double {
        nodes++
        if ((nodes and 1023L) == 0L && (cancelled || System.currentTimeMillis() > deadline)) throw SearchTimeout()
        var alpha = alphaIn
        var beta = betaIn
        val ms = Rules.moves(b, p)
        val opp = Rules.moves(b, -p)
        var empty = 0
        for (x in b) if (x == 0) empty++
        if (ms.isEmpty() && opp.isEmpty()) return (Rules.count(b, p) - Rules.count(b, -p)) * 10000.0
        if (depth <= 0 && empty > 10) return evalBoard(b, p)
        if (ms.isEmpty()) return -search(b, -p, depth, -beta, -alpha)
        if (depth <= 0) return evalBoard(b, p)
        val k = key(b, p)
        val entry = tt[k]
        val orig = alpha
        if (entry != null && entry.depth >= depth) {
            if (entry.flag == 0) return entry.score
            if (entry.flag == 1) alpha = maxOf(alpha, entry.score) else beta = minOf(beta, entry.score)
            if (alpha >= beta) return entry.score
        }
        var best = Double.NEGATIVE_INFINITY
        var bestMove = -1
        val ordered = orderMoves(b, p, ms, entry?.move ?: -1, minOf(depth, 63))
        for (m in ordered) {
            val v = -search(Rules.play(b, m, p), -p, depth - 1, -beta, -alpha)
            if (v > best) { best = v; bestMove = m }
            if (v > alpha) alpha = v
            if (alpha >= beta) {
                if (killers[depth][0] != m) killers[depth] = intArrayOf(m, killers[depth][0])
                break
            }
        }
        val flag = if (best <= orig) 2 else if (best >= beta) 1 else 0
        tt[k] = TTEntry(depth, best, bestMove, flag)
        if (tt.size > 180000) tt.clear()
        return best
    }

    /**
     * @param allowed agar diya jaye (screen ke hints) to sirf unhi chaalon mein se chunta hai.
     * @param onProgress har mukammal depth ke baad best move batata hai.
     */
    fun run(
        board: IntArray, side: Int, budgetMs: Long, maxDepth: Int,
        allowed: IntArray? = null,
        onProgress: ((SearchResult) -> Unit)? = null
    ): SearchResult {
        cancelled = false
        deadline = System.currentTimeMillis() + budgetMs
        nodes = 0
        tt.clear()
        killers = Array(64) { intArrayOf(-1, -1) }
        var root = Rules.moves(board, side)
        if (allowed != null && allowed.isNotEmpty()) {
            val f = root.filter { it in allowed }
            if (f.isNotEmpty()) root = f.toIntArray()
        }
        if (root.isEmpty()) return SearchResult(-1, 0, 0, 0.0)
        var best = root[0]
        var bestScore = Double.NEGATIVE_INFINITY
        var completed = 0
        for (d in 1..maxDepth) {
            try {
                var alpha = Double.NEGATIVE_INFINITY
                val beta = Double.POSITIVE_INFINITY
                var localMove = best
                var localScore = Double.NEGATIVE_INFINITY
                val ent = tt[key(board, side)]
                val ordered = orderMoves(board, side, root, ent?.move ?: -1, minOf(d, 63))
                for (m in ordered) {
                    val s = -search(Rules.play(board, m, side), -side, d - 1, -beta, -alpha)
                    if (s > localScore) { localScore = s; localMove = m }
                    if (s > alpha) alpha = s
                }
                best = localMove
                bestScore = localScore
                completed = d
                onProgress?.invoke(SearchResult(best, completed, nodes, bestScore))
                if (cancelled || System.currentTimeMillis() > deadline) break
            } catch (e: SearchTimeout) {
                break
            }
        }
        return SearchResult(best, completed, nodes, bestScore)
    }
}
