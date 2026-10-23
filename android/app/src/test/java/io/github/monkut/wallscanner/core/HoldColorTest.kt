package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Hold colour must come from the hold itself, not from wall colour bled into its rim. */
class HoldColorTest {
    private val red = intArrayOf(220, 30, 30)

    /**
     * Gray 1 × 1 m wall with one 6 cm hemispherical hold at its centre. [colorAt] gives the colour by height
     * above the wall; only heights from [Tuning.MIN_HOLD_HEIGHT] (2 cm) up are hold candidates.
     */
    private fun wallWithHold(colorAt: (Float) -> IntArray): VoxelCloud {
        val map = VoxelMap()
        val rnd = Random(5)
        val noise = { (rnd.nextFloat() - 0.5f) * 0.004f }
        SyntheticWall.addPanel(map, 1f, 1f, noise, holes = listOf(Triple(0.5f, 0.5f, 0.06f)))
        SyntheticWall.addHemisphere(map, 0.5f, 0.5f, 0.06f, SyntheticWall.gray, noise, colorAt = colorAt)
        return map.snapshot()
    }

    private fun detectOne(cloud: VoxelCloud): Hold {
        val result = SceneBuilder.build(cloud, listOf(floatArrayOf(0.5f, 0.5f, 1.5f)))!!
        assertEquals("one hold", 1, result.holds.size)
        return result.holds[0]
    }

    @Test
    fun redHoldWithGrayRimStaysRed() {
        // Wall colour up to 4 cm: half of the candidate band (2–6 cm) is grey, so the plain mean of the
        // candidates is a desaturated pink (sat ≈ 0.5) while the saturated half is the red cap.
        val hold = detectOne(wallWithHold { height -> if (height < 0.04f) SyntheticWall.gray else red })
        assertTrue("saturation ${hold.sat}", hold.sat > 0.8f)
        assertTrue("red channel ${hold.r}", hold.r > 200)
        assertTrue("hue ${hold.hue}", hold.hue < 5f || hold.hue > 355f)
        assertTrue("grouped as a chromatic hold", hold.sat >= Tuning.MIN_ROUTE_SATURATION)
    }

    @Test
    fun grayHoldWithColourNoiseStaysGray() {
        // Real grey holds carry per-voxel colour noise; keeping the more saturated half must not lift the
        // hold over the route threshold.
        val jitter = Random(9)
        val hold = detectOne(
            wallWithHold { IntArray(3) { SyntheticWall.gray[it] + jitter.nextInt(-20, 21) } }
        )
        assertTrue("saturation ${hold.sat}", hold.sat < Tuning.MIN_ROUTE_SATURATION)
        assertTrue("saturation ${hold.sat} should stay near the noise level", hold.sat < 0.2f)
    }
}
