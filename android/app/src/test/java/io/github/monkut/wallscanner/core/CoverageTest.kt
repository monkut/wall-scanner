package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoverageTest {
    @Test
    fun reportsTheCornerTheSweepSkipped() {
        // 3 × 3 m wall on z = 0 sampled every 2 cm, except the top-right metre, which gets a few stray points.
        val map = VoxelMap()
        val rnd = Random(4)
        var x = 0f
        while (x <= 3f) {
            var y = 0f
            while (y <= 3f) {
                val topRight = x > 2f && y > 2f
                if (!topRight || rnd.nextFloat() < 0.05f) map.add(x, y, 0f, 150, 150, 150)
                y += 0.02f
            }
            x += 0.02f
        }
        val frame = WallFrame(Plane(0f, 0f, 0f, 0f, 0f, 1f))
        val thin = Coverage.thinCells(map.snapshot(), frame, floatArrayOf(0f, 0f, 3f, 3f))
        assertEquals("one thin cell, got ${thin.map { "(${it.row}, ${it.col})" }}", 1, thin.size)
        assertEquals(0, thin[0].row)
        assertEquals(2, thin[0].col)
    }

    @Test
    fun fullCoverageReportsNothing() {
        val cloud = SyntheticWall.cloud()
        val frame = WallFrame(Plane(0f, 0f, 0f, 0f, 0f, 1f))
        assertTrue(Coverage.thinCells(cloud, frame, floatArrayOf(0f, 0f, 3f, 2.5f)).isEmpty())
    }
}
