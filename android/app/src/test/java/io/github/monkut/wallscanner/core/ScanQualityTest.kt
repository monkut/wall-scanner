package io.github.monkut.wallscanner.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Numbers from the phone's scan.log RESULT lines and scene.json extents (#35). */
class ScanQualityTest {
    @Test
    fun theDriftedMainWallScanIsFlagged() {
        // 2026-11-06 12:25: the orthophoto repeats its right half, every hold twice
        val q = ScanQuality.of(57592, 692957, 126, floatArrayOf(0f, 0f, 3.9f, 2.23f))
        assertTrue(q.toString(), q.drifted)
    }

    @Test
    fun goodScansAreNot() {
        // Nov 5 Main wall, one face
        assertFalse(ScanQuality.of(169190, 685430, 77, floatArrayOf(0f, 0f, 4.4f, 3.52f)).drifted)
        // 2026-11-06 12:13: the lowest surface share of a good scan (15.7%)
        assertFalse(ScanQuality.of(150000, 957570, 69, floatArrayOf(0f, 0f, 3.4f, 3.2f)).drifted)
        // Oct 29 5:24 PM: the densest good scan
        assertFalse(ScanQuality.of(188000, 545000, 61, floatArrayOf(0f, 0f, 3.2f, 2.5f)).drifted)
    }

    @Test
    fun oneSignalAloneIsNotDrift() {
        val lowShareOnly = ScanQuality.of(50, 1000, 20, floatArrayOf(0f, 0f, 2f, 2f))
        val denseOnly = ScanQuality.of(300, 1000, 60, floatArrayOf(0f, 0f, 2f, 2f))
        assertFalse(lowShareOnly.drifted)
        assertFalse(denseOnly.drifted)
    }

    @Test
    fun theSurfaceShareCountsEveryFace() {
        // a 3 × 2.5 m synthetic wall: nearly all of its points lie on the surface
        val cloud = SyntheticWall.cloud()
        val result = SceneBuilder.build(cloud, listOf(SyntheticWall.cameraInFront), kotlin.random.Random(7))!!
        val q = ScanQuality.of(cloud, result.surface, result.holds.size, result.extent)
        assertTrue(q.toString(), q.planeShare > 0.9f)
        assertFalse(q.drifted)
    }

    @Test
    fun anEmptySceneIsNotDrift() {
        assertFalse(ScanQuality.of(0, 0, 0, floatArrayOf(0f, 0f, 0f, 0f)).drifted)
    }
}
