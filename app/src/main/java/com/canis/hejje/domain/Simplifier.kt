package com.canis.hejje.domain

import kotlin.math.hypot

/** Douglas-Peucker line simplification on local metric coordinates. */
object Simplifier {
    /**
     * @param xs,ys point coordinates in metres
     * @param forced points that must be kept (turn/stop anchors, gap edges)
     * @return indices to keep, ascending
     */
    fun simplify(xs: DoubleArray, ys: DoubleArray, epsilon: Double, forced: BooleanArray? = null): IntArray {
        val n = xs.size
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        forced?.forEachIndexed { i, f -> if (f) keep[i] = true }
        val anchors = (0 until n).filter { keep[it] }
        val stack = ArrayDeque<Long>()
        for (k in 0 until anchors.size - 1) stack.addLast(pack(anchors[k], anchors[k + 1]))
        while (stack.isNotEmpty()) {
            val range = stack.removeLast()
            val a = (range shr 32).toInt()
            val b = range.toInt()
            if (b - a < 2) continue
            var worst = -1
            var worstDist = epsilon
            for (i in a + 1 until b) {
                val d = segmentDistance(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > worstDist) {
                    worstDist = d
                    worst = i
                }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(pack(a, worst))
                stack.addLast(pack(worst, b))
            }
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    private fun pack(a: Int, b: Int): Long = (a.toLong() shl 32) or b.toLong()

    fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Evenly thins [count] items down to at most [max] indices, always keeping both ends. */
    fun thin(count: Int, max: Int): IntArray {
        if (count <= max) return IntArray(count) { it }
        val out = IntArray(max)
        for (i in 0 until max) out[i] = Math.round(i * (count - 1).toDouble() / (max - 1)).toInt()
        return out
    }
}
