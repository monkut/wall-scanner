package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HoldOutlineTest {
    private fun area(pts: FloatArray): Float {
        var a = 0f
        val n = pts.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1]
        }
        return abs(a) / 2
    }

    @Test
    fun squareBlockTracesItsBoundary() {
        // 4 × 4 cells of 2 cm at grid origin (3, 5): an 8 × 8 cm square from u = 6 cm, v = 10 cm.
        val cu = IntArray(16) { 3 + it % 4 }; val cv = IntArray(16) { 5 + it / 4 }
        val pts = HoldOutline.trace(IntArray(16) { it }, cu, cv, 0.02f)
        assertTrue("closed polygon with several points, got ${pts.size / 2}", pts.size / 2 >= 8)
        assertTrue(pts.size / 2 <= 64)
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (i in 0 until pts.size / 2) {
            minU = minOf(minU, pts[i * 2]); maxU = maxOf(maxU, pts[i * 2]); minV = minOf(minV, pts[i * 2 + 1]); maxV = maxOf(maxV, pts[i * 2 + 1])
        }
        assertEquals(0.06f, minU, 0.011f); assertEquals(0.14f, maxU, 0.011f)
        assertEquals(0.10f, minV, 0.011f); assertEquals(0.18f, maxV, 0.011f)
        // Corner rounding trims a little area; most of the 64 cm² must remain.
        assertTrue("area ${area(pts)}", area(pts) in 0.0050f..0.0064f)
    }

    @Test
    fun holeAndSeparatePieceDoNotBreakTheOuterLoop() {
        // Ring of cells (3 × 3 minus centre) plus one detached cell far away: the outer ring wins.
        val cells = ArrayList<IntArray>()
        for (y in 0 until 3) for (x in 0 until 3) if (!(x == 1 && y == 1)) cells.add(intArrayOf(x, y))
        cells.add(intArrayOf(10, 10))
        val cu = IntArray(cells.size) { cells[it][0] }; val cv = IntArray(cells.size) { cells[it][1] }
        val pts = HoldOutline.trace(IntArray(cells.size) { it }, cu, cv, 0.02f)
        for (i in 0 until pts.size / 2) assertTrue("point outside the ring: ${pts[i * 2]}, ${pts[i * 2 + 1]}", pts[i * 2] <= 0.061f && pts[i * 2 + 1] <= 0.061f)
        assertTrue("area ${area(pts)}", area(pts) > 0.0025f)
    }
}
