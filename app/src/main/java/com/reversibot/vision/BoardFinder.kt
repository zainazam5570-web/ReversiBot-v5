package com.reversibot.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Screen se board ko khud dhoondta hai (manual calibration ki zaroorat nahi).
 *
 * Tareeqa:
 * 1. Screen ko chhote blocks mein baant kar sab se zyada nazar aane wale rang (board ka rang) chunta hai.
 * 2. Har rang ke liye us rang ke connected region ka bounding box nikalta hai (discs aur grid lines
 *    region ko todte nahi, kyunke blocks aur closing istemal hoti hai).
 * 3. Box ke kinare pixel level par theek karta hai.
 * 4. Sirf square-numa boxes rakhta hai, aur jis box mein Reversi ka rule sahi nikle
 *    (board ke beech ke 4 squares hamesha discs hote hain) usay chunta hai. Sab se bara jeet-ta hai.
 */
class BoardFinder(private val detector: BoardDetector = BoardDetector()) {

    private class Cand(val geo: BoardGeometry, val score: Float)

    fun find(src: PixelSource): BoardGeometry? {
        val w = src.width
        val h = src.height
        val small = min(w, h)
        if (small < 200) return null

        // 1) Grid lines (9 barabar faasle wali lines) - kisi bhi rang/texture ke board par chalta hai
        for (g in gridCandidates(src)) {
            if (looksLikeBoard(src, g)) return g
        }
        // 2) Fallback: board ke rang se

        val step = max(2, small / 220)          // sampling gap in pixels
        val gw = w / step
        val gh = h / step
        val rgb = IntArray(gw * gh)
        for (gy in 0 until gh) {
            val y = gy * step + step / 2
            for (gx in 0 until gw) {
                rgb[gy * gw + gx] = src.pixel(gx * step + step / 2, y) and 0xFFFFFF
            }
        }

        val colors = dominantColors(rgb, 8)
        val found = ArrayList<Cand>()
        for (c in colors) {
            for (box in regionsOfColor(rgb, gw, gh, step, c)) {
                val g = refine(src, box, c, step) ?: continue
                val cw = g.right - g.left
                val ch = g.bottom - g.top
                if (cw < small * 0.30f || ch < small * 0.30f) continue
                if (abs(cw - ch) / max(cw, ch) > 0.08f) continue
                if (!looksLikeBoard(src, g)) continue
                found.add(Cand(g, cw * ch))
            }
        }
        var best = found.maxByOrNull { it.score }
        // Board ke bahar frame/border ka alag rang ho to bara box frame ka hota hai.
        // Agar us ke andar lagbhag utna hi bara doosra valid box ho to wohi asli board hai.
        while (best != null) {
            val outer = best
            val inner = found.filter {
                it !== outer && it.score >= outer.score * 0.70f && it.score < outer.score &&
                    it.geo.left >= outer.geo.left - 2f && it.geo.top >= outer.geo.top - 2f &&
                    it.geo.right <= outer.geo.right + 2f && it.geo.bottom <= outer.geo.bottom + 2f
            }.maxByOrNull { it.score } ?: break
            best = inner
        }
        // Pixel-level kinare tay hone ke baad board ko bilkul square bana do (cells barabar hon)
        return best?.geo?.let { squareUp(it, w, h) }
    }

    // ---------------------------------------------------------------- grid lines

    private class GridCand(val score: Float, val s: Float, val x0: Int, val y0: Int)

    /**
     * Board ke cells ke kinaron par har row/column mein brightness badalti hai. Isliye vertical edges ka
     * column-wise jama (Cx) aur horizontal edges ka row-wise jama (Cy) mein 9 barabar faasle wali
     * chotiyan aati hain. Aise (x0, y0, cell size s) dhoondta hai jin par 18 chotiyan ek saath milein.
     */
    private fun gridCandidates(src: PixelSource): List<BoardGeometry> {
        val w = src.width
        val h = src.height
        val lum = ByteArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                val p = src.pixel(x, y)
                lum[o + x] = ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
            }
        }
        fun l(x: Int, y: Int): Int = lum[y * w + x].toInt() and 0xFF

        val cx = FloatArray(w)
        val cy = FloatArray(h)
        var y = 2
        while (y < h - 2) {
            val o = y * w
            for (x in 2 until w - 2) {
                val d = abs((lum[o + x + 2].toInt() and 0xFF) - (lum[o + x - 2].toInt() and 0xFF))
                cx[x] += min(d, 60).toFloat()
            }
            y += 2
        }
        var x = 2
        while (x < w - 2) {
            for (yy in 2 until h - 2) {
                val d = abs(l(x, yy + 2) - l(x, yy - 2))
                cy[yy] += min(d, 60).toFloat()
            }
            x += 2
        }
        val mx = maxFilter(cx)
        val my = maxFilter(cy)

        val small = min(w, h)
        val sMin = small * 0.30f / 8f
        val sMax = min((w - 1) / 8f, (h - 1) / 8f)
        val found = ArrayList<GridCand>()
        var s = sMin
        while (s <= sMax) {
            val bx = bestOffset(mx, w, s)
            val by = bestOffset(my, h, s)
            if (bx != null && by != null) found.add(GridCand(bx.second + by.second, s, bx.first, by.first))
            s += 0.25f
        }
        found.sortByDescending { it.score }
        val out = ArrayList<GridCand>()
        for (c in found) {
            if (out.size >= 6) break
            if (out.any { abs(it.s - c.s) < 1.6f && abs(it.x0 - c.x0) < 8 && abs(it.y0 - c.y0) < 8 }) continue
            out.add(c)
        }
        return out.map { refineGrid(cx, cy, it, w, h) }
    }

    /** 3-tap jama par chhote ranges mein (x0, y0, s) ko barik kar ke lines ke bilkul beech par lao. */
    private fun refineGrid(cx: FloatArray, cy: FloatArray, c: GridCand, w: Int, h: Int): BoardGeometry {
        fun at(a: FloatArray, i: Int): Float =
            if (i < 1 || i >= a.size - 1) 0f else a[i - 1] + a[i] + a[i + 1]
        var bs = c.s; var bx = c.x0; var by = c.y0
        var bestScore = -1f
        var s = c.s - 0.5f
        while (s <= c.s + 0.5f) {
            for (dx in -3..3) for (dy in -3..3) {
                val x0 = c.x0 + dx; val y0 = c.y0 + dy
                if (x0 < 0 || y0 < 0 || x0 + 8 * s > w - 1 || y0 + 8 * s > h - 1) continue
                var sum = 0f
                for (i in 0..8) {
                    val o = (i * s + 0.5f).toInt()
                    sum += at(cx, x0 + o) + at(cy, y0 + o)
                }
                if (sum > bestScore) { bestScore = sum; bs = s; bx = x0; by = y0 }
            }
            s += 0.05f
        }
        return BoardGeometry(bx.toFloat(), by.toFloat(), bx + 8 * bs, by + 8 * bs)
    }

    private fun maxFilter(a: FloatArray): FloatArray {
        val o = FloatArray(a.size)
        for (i in a.indices) {
            var m = a[i]
            if (i > 0 && a[i - 1] > m) m = a[i - 1]
            if (i < a.size - 1 && a[i + 1] > m) m = a[i + 1]
            o[i] = m
        }
        return o
    }

    /** Best start offset aur uska score (9 lines ka jama). Koi ek line bahut kamzor ho to reject. */
    private fun bestOffset(m: FloatArray, n: Int, s: Float): Pair<Int, Float>? {
        var bestSum = 0f
        var bestPos = -1
        val last = n - 1 - (8 * s).toInt()
        for (x0 in 0..last) {
            var sum = 0f
            var mn = Float.MAX_VALUE
            for (i in 0..8) {
                val v = m[x0 + (i * s + 0.5f).toInt()]
                sum += v
                if (v < mn) mn = v
            }
            if (mn < 0.25f * sum / 9f) continue
            if (sum > bestSum) { bestSum = sum; bestPos = x0 }
        }
        return if (bestPos < 0) null else Pair(bestPos, bestSum)
    }

    /** Beech ke 4 squares (d4,e4,d5,e5) har Reversi position mein discs hote hain. */
    fun looksLikeBoard(src: PixelSource, g: BoardGeometry): Boolean {
        if (g.left < 0 || g.top < 0 || g.right > src.width + 1 || g.bottom > src.height + 1) return false
        val r = detector.read(src, g)
        return isPlausible(r)
    }

    private fun squareUp(g: BoardGeometry, w: Int, h: Int): BoardGeometry {
        val side = (g.right - g.left + g.bottom - g.top) / 2f
        val cx = (g.left + g.right) / 2f
        val cy = (g.top + g.bottom) / 2f
        val l = (cx - side / 2f).coerceIn(0f, w - side)
        val t = (cy - side / 2f).coerceIn(0f, h - side)
        return BoardGeometry(l, t, l + side, t + side)
    }

    // ---------------------------------------------------------------- colors

    private fun dominantColors(rgb: IntArray, maxCount: Int): List<Int> {
        val cnt = IntArray(4096)
        val sr = LongArray(4096); val sg = LongArray(4096); val sb = LongArray(4096)
        for (p in rgb) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            cnt[k]++; sr[k] += r.toLong(); sg[k] += g.toLong(); sb[k] += b.toLong()
        }
        val order = (0 until 4096).filter { cnt[it] > rgb.size / 400 }.sortedByDescending { cnt[it] }
        val out = ArrayList<Int>()
        for (k in order) {
            val n = cnt[k]
            val c = ((sr[k] / n).toInt() shl 16) or ((sg[k] / n).toInt() shl 8) or (sb[k] / n).toInt()
            if (out.none { dist(it, c) < 40f }) out.add(c)
            if (out.size >= maxCount) break
        }
        return out
    }

    private fun dist(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    // ---------------------------------------------------------------- regions

    /** Pixel box (left, top, right, bottom) */
    private class Box(val l: Int, val t: Int, val r: Int, val b: Int)

    private fun regionsOfColor(rgb: IntArray, gw: Int, gh: Int, step: Int, color: Int): List<Box> {
        val blk = 3                                  // block = 3x3 samples
        val bw = gw / blk
        val bh = gh / blk
        if (bw < 4 || bh < 4) return emptyList()
        val on = BooleanArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var n = 0
            for (y in 0 until blk) for (x in 0 until blk) {
                if (dist(rgb[(by * blk + y) * gw + bx * blk + x], color) < TOL) n++
            }
            on[by * bw + bx] = n * 100 >= blk * blk * 30
        }
        // closing: ek block failao taake lines/discs region na todein
        val dil = BooleanArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            if (!on[by * bw + bx]) continue
            for (dy in -1..1) for (dx in -1..1) {
                val x = bx + dx; val y = by + dy
                if (x in 0 until bw && y in 0 until bh) dil[y * bw + x] = true
            }
        }
        val seen = BooleanArray(bw * bh)
        val out = ArrayList<Box>()
        val stack = IntArray(bw * bh)
        for (s in 0 until bw * bh) {
            if (!dil[s] || seen[s]) continue
            var sp = 0
            stack[sp++] = s; seen[s] = true
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % bw; val cy = cur / bw
                if (on[cur]) {
                    minX = min(minX, cx); maxX = max(maxX, cx)
                    minY = min(minY, cy); maxY = max(maxY, cy)
                }
                for (dy in -1..1) for (dx in -1..1) {
                    val x = cx + dx; val y = cy + dy
                    if (x < 0 || y < 0 || x >= bw || y >= bh) continue
                    val i = y * bw + x
                    if (dil[i] && !seen[i]) { seen[i] = true; stack[sp++] = i }
                }
            }
            if (maxX < 0) continue
            val px = blk * step
            val box = Box(minX * px, minY * px, (maxX + 1) * px, (maxY + 1) * px)
            val bwPx = box.r - box.l
            val bhPx = box.b - box.t
            // coarse squareness (pixel-level refine ke baad dobara sakht check hota hai)
            if (bwPx < 60 || bhPx < 60) continue
            if (abs(bwPx - bhPx).toFloat() / max(bwPx, bhPx) > 0.25f) continue
            out.add(box)
        }
        return out
    }

    // ---------------------------------------------------------------- refine

    private fun matches(src: PixelSource, x: Int, y: Int, color: Int): Boolean {
        if (x < 0 || y < 0 || x >= src.width || y >= src.height) return false
        return dist(src.pixel(x, y) and 0xFFFFFF, color) < TOL
    }

    private fun refine(src: PixelSource, box: Box, color: Int, step: Int): BoardGeometry? {
        val m = step * 9                              // itna aage-peechay dekho
        val spanX0 = box.l + (box.r - box.l) / 5
        val spanX1 = box.r - (box.r - box.l) / 5
        val spanY0 = box.t + (box.b - box.t) / 5
        val spanY1 = box.b - (box.b - box.t) / 5
        val sx = max(1, (spanX1 - spanX0) / 40)
        val sy = max(1, (spanY1 - spanY0) / 40)

        fun colFrac(x: Int): Float {
            var n = 0; var hit = 0; var y = spanY0
            while (y <= spanY1) { n++; if (matches(src, x, y, color)) hit++; y += sy }
            return if (n == 0) 0f else hit.toFloat() / n
        }
        fun rowFrac(y: Int): Float {
            var n = 0; var hit = 0; var x = spanX0
            while (x <= spanX1) { n++; if (matches(src, x, y, color)) hit++; x += sx }
            return if (n == 0) 0f else hit.toFloat() / n
        }

        // andar ki taraf scan: pehla column/row jahan rang ka hissa >= 60%
        var left = -1
        for (x in (box.l - m)..(box.l + m)) if (colFrac(x) >= 0.6f) { left = x; break }
        var right = -1
        for (x in (box.r + m) downTo (box.r - m)) if (colFrac(x) >= 0.6f) { right = x + 1; break }
        var top = -1
        for (y in (box.t - m)..(box.t + m)) if (rowFrac(y) >= 0.6f) { top = y; break }
        var bottom = -1
        for (y in (box.b + m) downTo (box.b - m)) if (rowFrac(y) >= 0.6f) { bottom = y + 1; break }
        if (left < 0 || right < 0 || top < 0 || bottom < 0) return null
        if (right - left < 60 || bottom - top < 60) return null
        return BoardGeometry(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    companion object {
        private const val TOL = 42f

        /** Beech ke 4 squares (27, 28, 35, 36) mein discs hon aur kul discs 4..64 hon. */
        fun isPlausible(r: Reading): Boolean {
            val c = intArrayOf(27, 28, 35, 36)
            if (c.any { r.board[it] == 0 }) return false
            val discs = r.board.count { it != 0 }
            return discs in 4..64
        }
    }
}
