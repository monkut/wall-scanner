package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The orthophoto paints only where depth was measured nearby (#65). */
class OrthophotoTest {
    @Test
    fun aGapWithNoDepthStaysUnseen() {
        // The wall scanned at x 0..1 and 2..3, nothing in between: a sweep that jumped across part of the wall.
        val left = SyntheticWall.cloud(0f, 1f); val right = SyntheticWall.cloud(2f, 3f)
        val count = left.count + right.count
        val cloud = VoxelCloud(count, left.xyz + right.xyz, left.rgb + right.rgb, left.hits + right.hits)
        val result = SceneBuilder.build(cloud, listOf(SyntheticWall.cameraInFront), kotlin.random.Random(7))!!
        val pose = Rigid(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(1.5, 1.2, 2.0))
        val frame = Keyframe("a", pose, 500f, 500f, 320f, 240f, 640, 480)
        val photo = OrthophotoBuilder.build(cloud, result.surface, result.extent, listOf(frame), decode = { Image(640, 480, IntArray(640 * 480) { 0xffC9B496.toInt() }) })!!
        val s = result.surface
        fun source(x: Float, y: Float): Int {
            val i = photo.y(s.v(x, y, 0f)).toInt() * photo.image.width + photo.x(s.u(x, y, 0f)).toInt()
            return photo.source!![i]
        }
        assertEquals(0, source(0.5f, 1.2f))
        assertEquals(0, source(1.15f, 1.2f)) // 15 cm from the last points: within NO_DEPTH_RADIUS
        assertEquals(-1, source(1.5f, 1.2f))
        assertTrue(photo.ghost!![photo.y(s.v(1.5f, 1.2f, 0f)).toInt() * photo.image.width + photo.x(s.u(1.5f, 1.2f, 0f)).toInt()].isNaN())
        assertEquals(0, source(2.5f, 1.2f))
    }
}
