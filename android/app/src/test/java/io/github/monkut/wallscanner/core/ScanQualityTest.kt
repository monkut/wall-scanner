package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
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
    fun keyframesDisagreeingOverARegionMeanDrift() {
        // 2026-11-06 12:25: 16.1% of the wall, 31–53% in three cells of the right half
        val cells = listOf(Coverage.Cell(1, 2), Coverage.Cell(2, 1), Coverage.Cell(2, 2))
        val ghosted = ScanQuality.of(200000, 700000, 60, floatArrayOf(0f, 0f, 4f, 3f), ghostShare = 0.161f, ghostCells = cells)
        assertTrue(ghosted.toString(), ghosted.drifted && ghosted.ghosted && !ghosted.smeared)
        // 12:17, the worst good scan: 9.3% overall, one cell at 23% — under both thresholds, no cell named
        assertFalse(ScanQuality.of(200000, 700000, 60, floatArrayOf(0f, 0f, 4f, 3f), ghostShare = 0.093f).drifted)
        // a high share with no cell past the cell threshold is not localised drift
        assertFalse(ScanQuality.of(200000, 700000, 60, floatArrayOf(0f, 0f, 4f, 3f), ghostShare = 0.13f).ghosted)
        // no ghost map: judged on the cloud alone
        assertFalse(ScanQuality.of(200000, 700000, 60, floatArrayOf(0f, 0f, 4f, 3f)).ghosted)
    }

    @Test
    fun theGhostMapIsReadPerCell() {
        // a 3 × 3 m wall at 5 mm: the right third's pixels disagree, the rest agree
        val n = 600; val px = 0.005f
        val ghost = FloatArray(n * n) { i -> if (i % n >= 400) 40f else 5f }
        val photo = Orthophoto(Image(n, n, IntArray(n * n)), 0f, 0f, 3f, 3f, px, FloatArray(n * n), ghost = ghost)
        val (share, cells) = ScanQuality.ghost(photo, floatArrayOf(0f, 0f, 3f, 3f))
        assertEquals(0.333f, share, 0.01f)
        assertEquals(listOf(Coverage.Cell(0, 2), Coverage.Cell(1, 2), Coverage.Cell(2, 2)).map { it.row to it.col }, cells.map { it.row to it.col })
    }

    @Test
    fun anEmptySceneIsNotDrift() {
        assertFalse(ScanQuality.of(0, 0, 0, floatArrayOf(0f, 0f, 0f, 0f)).drifted)
    }

    @Test
    fun aSparselyComparedCellIsNeverNamed() {
        // Agreement everywhere two views exist, except a top-right corner only three pixels of which were compared, two disagreeing.
        val n = 600; val px = 0.005f
        val ghost = FloatArray(n * n) { i -> if (i % n >= 400 && i / n < 200) Float.NaN else 5f }
        ghost[10 * n + 450] = 40f; ghost[11 * n + 450] = 40f; ghost[12 * n + 450] = 5f
        val photo = Orthophoto(Image(n, n, IntArray(n * n)), 0f, 0f, 3f, 3f, px, FloatArray(n * n), ghost = ghost)
        val (share, cells) = ScanQuality.ghost(photo, floatArrayOf(0f, 0f, 3f, 3f))
        assertTrue(share < 0.01f)
        assertTrue(cells.map { it.row to it.col }.toString(), cells.isEmpty())
    }

    @Test
    fun theGhostMapComparesTheTwoBestViews() {
        val cloud = SyntheticWall.cloud()
        val result = SceneBuilder.build(cloud, listOf(SyntheticWall.cameraInFront), kotlin.random.Random(7))!!
        // Two keyframes from the same spot 2 m in front of the wall, each seeing it in one flat colour.
        val pose = Rigid(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(1.5, 1.2, 2.0))
        fun frame(name: String) = Keyframe(name, pose, 500f, 500f, 320f, 240f, 640, 480)
        val colors = mapOf("a" to 0xffC9B496.toInt(), "b" to 0xff5A3C9E.toInt(), "c" to 0xffC9B496.toInt())
        fun build(vararg names: String) = OrthophotoBuilder.build(cloud, result.surface, result.extent, names.map { frame(it) }, decode = { k -> Image(640, 480, IntArray(640 * 480) { colors.getValue(k.file) }) })!!
        val lone = build("a").ghost!!
        assertTrue(lone.all { it.isNaN() })
        val agree = build("a", "c").ghost!!.filter { !it.isNaN() }
        assertTrue(agree.isNotEmpty() && agree.all { it == 0f })
        val differ = build("a", "b").ghost!!.filter { !it.isNaN() }
        val expected = ColorUtil.deltaE(ColorUtil.rgbToLab(0xC9, 0xB4, 0x96), ColorUtil.rgbToLab(0x5A, 0x3C, 0x9E))
        assertEquals(agree.size, differ.size)
        assertTrue(expected > Tuning.GHOST_DE)
        assertTrue(differ.all { kotlin.math.abs(it - expected) < 0.01f })
    }
}
