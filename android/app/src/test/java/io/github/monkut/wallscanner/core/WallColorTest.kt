package io.github.monkut.wallscanner.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** The wall's hue is the most common one on the plane, not a mean that the holds' skirts pull away. */
class WallColorTest {
    @Test
    fun holdSkirtsDoNotPullTheWallHue() {
        // 60% tan plywood (hue 35), 20% purple and 20% red hold skirts, all on the plane z = 0.
        val colors = listOf(intArrayOf(0xC9, 0xB4, 0x96), intArrayOf(0x80, 0x40, 0xC0), intArrayOf(0xC0, 0x30, 0x30))
        val n = 1000
        val xyz = FloatArray(n * 3) { if (it % 3 == 0) (it / 3) * 0.001f else 0f }
        val rgb = ByteArray(n * 3) { colors[when ((it / 3) % 5) { 3 -> 1; 4 -> 2; else -> 0 }][it % 3].toByte() }
        val wall = WallColor.of(VoxelCloud(n, xyz, rgb, IntArray(n) { 1 }), WallSurface.single(WallFrame(Plane(0f, 0f, 0f, 0f, 0f, 1f))))!!
        assertTrue("hue ${wall.hue}", wall.hue in 30f..40f)
        assertTrue("tan matches", wall.matches(0xC9, 0xB4, 0x96))
    }
}
