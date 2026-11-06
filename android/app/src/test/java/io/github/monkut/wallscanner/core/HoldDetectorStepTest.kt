package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A step of the wall (a boxed-out section standing 0.5–1 m off the plane, #28) is not a hold; a wide, low
 * volume still is. Three hemispherical holds elsewhere on the wall are found either way. Scraps of the step's
 * riser under [Tuning.STEP_MIN_EXTENT] may come through (they do on the real walls too): a narrow box standing
 * 1 m proud is also the shape of the Oct 21 reference's corner volume, so height alone cannot drop them.
 */
class HoldDetectorStepTest {
    private val holds = listOf(Triple(0.5f, 0.5f, SyntheticWall.red), Triple(1.5f, 0.9f, SyntheticWall.red), Triple(0.8f, 1.8f, SyntheticWall.red))
    private val boxX = 1.9f; private val boxY = 1.5f; private val boxSize = 0.8f

    /** The synthetic wall with a box of [top] height over its (x, y) at the top right; wall under the box unseen. */
    private fun cloud(top: (Float, Float) -> Float): VoxelCloud {
        val map = VoxelMap()
        val rnd = Random(3)
        val noise = { (rnd.nextFloat() - 0.5f) * 0.004f }
        val boxHole = Triple(boxX + boxSize / 2, boxY + boxSize / 2, boxSize / 2 * 1.42f)
        SyntheticWall.addPanel(map, 3f, 2.5f, noise, holes = holds.map { (hx, hy, _) -> Triple(hx, hy, 0.06f) } + boxHole)
        for ((hx, hy, color) in holds) SyntheticWall.addHemisphere(map, hx, hy, 0.06f, color, noise)
        val brown = intArrayOf(120, 90, 70)
        var x = boxX
        while (x <= boxX + boxSize) {
            var y = boxY
            while (y <= boxY + boxSize) {
                map.add(x, y, top(x, y) + noise(), brown[0], brown[1], brown[2]) // the top
                for (edge in listOf(x == boxX, y == boxY)) if (edge) { // two risers, seen from the front-left
                    var z = 0f
                    while (z < top(x, y)) { map.add(x, y, z + noise(), brown[0], brown[1], brown[2]); z += 0.005f }
                }
                y += 0.005f
            }
            x += 0.005f
        }
        return map.snapshot()
    }

    private fun build(cloud: VoxelCloud) = SceneBuilder.build(cloud, listOf(SyntheticWall.cameraInFront), Random(7))!!

    @Test
    fun aStepOfTheWallIsNotAHold() {
        val result = build(cloud { x, _ -> 0.5f + 0.5f * (x - boxX) / boxSize }) // 0.5 m at its riser, 1 m at the far edge
        val found = holds.count { (hx, hy, _) -> result.holds.any { kotlin.math.abs(it.cx - hx) < 0.02f && kotlin.math.abs(it.cy - hy) < 0.02f } }
        assertEquals("holds: ${result.holds.map { "%.2f×%.2f".format(it.width, it.tall) }}", 3, found)
        val onStep = result.holds.filter { it.cx > boxX - 0.1f && it.cy > boxY - 0.1f }
        assertTrue("step pieces: ${onStep.map { "%.2f×%.2f".format(it.width, it.tall) }}", onStep.all { maxOf(it.width, it.tall) < Tuning.STEP_MIN_EXTENT })
        assertTrue("step outlines: ${result.steps.size}", result.steps.isNotEmpty())
    }


    @Test
    fun aWideLowVolumeIsAHold() {
        val result = build(cloud { _, _ -> 0.3f })
        val volume = result.holds.filter { it.cx > boxX - 0.1f && it.cy > boxY - 0.1f }
        assertEquals("holds: ${result.holds.map { "%.2f×%.2f".format(it.width, it.tall) }}", 1, volume.size)
        assertTrue("width ${volume[0].width}", volume[0].width >= Tuning.STEP_MIN_EXTENT)
        assertEquals(4, result.holds.size)
    }
}
