package io.github.monkut.wallscanner.core

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Outline of a hold from the relief-map cells it occupies: the outer boundary of the cell set, traced along
 * cell edges and rounded with two Chaikin passes so a 2 cm grid reads as the hold's actual shape. Points are
 * (u, v) in metres, interleaved, forming a closed polygon of at most [MAX_POINTS] vertices.
 */
object HoldOutline {
    private const val MAX_POINTS = 64
    private const val SMOOTH_PASSES = 2

    /** [cells] index [cu]/[cv] (grid coordinates of [cellSize] cells); empty input gives an empty array. */
    fun trace(cells: IntArray, cu: IntArray, cv: IntArray, cellSize: Float, maxPoints: Int = MAX_POINTS, smoothPasses: Int = SMOOTH_PASSES): FloatArray {
        if (cells.isEmpty()) return FloatArray(0)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (c in cells) {
            if (cu[c] < minX) minX = cu[c]; if (cu[c] > maxX) maxX = cu[c]
            if (cv[c] < minY) minY = cv[c]; if (cv[c] > maxY) maxY = cv[c]
        }
        // Padded local grid so every cell has a neighbour to test against.
        val w = maxX - minX + 3; val h = maxY - minY + 3
        val filled = BooleanArray(w * h)
        for (c in cells) filled[(cv[c] - minY + 1) * w + (cu[c] - minX + 1)] = true

        // Directed boundary edges with the filled cell on their left: an anticlockwise outer loop.
        val vertices = w + 1
        val next = HashMap<Int, ArrayList<Int>>()
        fun key(px: Int, py: Int) = py * vertices + px
        fun edge(ax: Int, ay: Int, bx: Int, by: Int) = next.getOrPut(key(ax, ay)) { ArrayList(2) }.add(key(bx, by))
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if (!filled[y * w + x]) continue
            if (!filled[(y - 1) * w + x]) edge(x, y, x + 1, y)
            if (!filled[y * w + x + 1]) edge(x + 1, y, x + 1, y + 1)
            if (!filled[(y + 1) * w + x]) edge(x + 1, y + 1, x, y + 1)
            if (!filled[y * w + x - 1]) edge(x, y + 1, x, y)
        }
        // Chain edges into loops; keep the loop enclosing the largest area (the outer boundary).
        var best: IntArray? = null
        var bestArea = -1.0
        while (next.isNotEmpty()) {
            val start = next.keys.first()
            val loop = ArrayList<Int>()
            var at = start
            while (true) {
                loop.add(at)
                val out = next[at] ?: break
                val to = out.removeAt(out.size - 1)
                if (out.isEmpty()) next.remove(at)
                at = to
                if (at == start) break
            }
            val area = abs(shoelace(loop, vertices))
            if (area > bestArea) { bestArea = area; best = loop.toIntArray() }
        }
        val loop = best ?: return FloatArray(0)
        var pts = FloatArray(loop.size * 2)
        for ((i, k) in loop.withIndex()) {
            pts[i * 2] = (minX - 1 + k % vertices) * cellSize
            pts[i * 2 + 1] = (minY - 1 + k / vertices) * cellSize
        }
        repeat(smoothPasses) { pts = chaikin(pts) }
        val n = pts.size / 2
        val step = ceil(n / maxPoints.toFloat()).toInt().coerceAtLeast(1)
        val kept = (n + step - 1) / step
        val out = FloatArray(kept * 2)
        for (i in 0 until kept) { out[i * 2] = pts[i * step * 2]; out[i * 2 + 1] = pts[i * step * 2 + 1] }
        return out
    }

    private fun shoelace(loop: List<Int>, vertices: Int): Double {
        var area = 0.0
        for (i in loop.indices) {
            val a = loop[i]; val b = loop[(i + 1) % loop.size]
            area += (a % vertices).toDouble() * (b / vertices) - (b % vertices).toDouble() * (a / vertices)
        }
        return area / 2
    }

    /** One Chaikin corner-cutting pass over a closed polygon (doubles the point count). */
    private fun chaikin(pts: FloatArray): FloatArray {
        val n = pts.size / 2
        val out = FloatArray(n * 4)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]; val bx = pts[j * 2]; val by = pts[j * 2 + 1]
            out[i * 4] = 0.75f * ax + 0.25f * bx; out[i * 4 + 1] = 0.75f * ay + 0.25f * by
            out[i * 4 + 2] = 0.25f * ax + 0.75f * bx; out[i * 4 + 3] = 0.25f * ay + 0.75f * by
        }
        return out
    }
}
