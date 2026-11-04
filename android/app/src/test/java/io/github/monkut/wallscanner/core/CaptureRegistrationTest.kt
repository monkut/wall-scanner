package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** A second scan of the same wall, turned and shifted, is registered back onto the first from its holds and cloud. */
class CaptureRegistrationTest {
    private val random = Random(7)

    /** A flat wall z = 0 with bumps: holds as small raised discs; returns the cloud and the holds. */
    private fun wall(): Pair<VoxelCloud, List<HoldPoint>> {
        val holds = List(14) { i -> HoldPoint(-1.2f + i * 0.19f, 0.2f + (i % 5) * 0.35f, 0.03f, (i * 47f) % 360f, 0.8f, 0.7f, 0.12f) }
        val pts = ArrayList<Float>()
        var x = -1.5f
        while (x <= 1.5f) {
            var y = 0f
            while (y <= 2f) {
                var z = 0f
                for (h in holds) if (abs(h.x - x) < 0.06f && abs(h.y - y) < 0.06f) z = 0.03f
                pts += x; pts += y; pts += z + (random.nextFloat() - 0.5f) * 0.004f
                y += 0.015f
            }
            x += 0.015f
        }
        val n = pts.size / 3
        return VoxelCloud(n, pts.toFloatArray(), ByteArray(n * 3) { 100 }, IntArray(n) { 2 }) to holds
    }

    @Test
    fun recoversYawAndShiftOfASecondScan() {
        val (cloud, holds) = wall()
        // The second session's world is the first turned by -20° about the vertical and moved by (0.8, -0.3, 0.5),
        // seeing the right 60% of the wall only.
        val yaw = Math.toRadians(-20.0)
        val toSecond = CaptureRegistration.yawRigid(yaw, 0.8, -0.3, 0.5)
        val keep = (0 until cloud.count).filter { cloud.xyz[it * 3] > -0.3f }
        val xyz = FloatArray(keep.size * 3); val rgb = ByteArray(keep.size * 3)
        for ((k, i) in keep.withIndex()) { xyz[k * 3] = cloud.xyz[i * 3]; xyz[k * 3 + 1] = cloud.xyz[i * 3 + 1]; xyz[k * 3 + 2] = cloud.xyz[i * 3 + 2] }
        val second = toSecond.apply(VoxelCloud(keep.size, xyz, rgb, IntArray(keep.size) { 2 }))
        val secondHolds = holds.filter { it.x > -0.3f }.map { h ->
            val p = DoubleArray(3); toSecond.apply(h.x.toDouble(), h.y.toDouble(), h.z.toDouble(), p)
            HoldPoint(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), h.hue, h.sat, h.value, h.size)
        }
        val reg = CaptureRegistration.register(cloud, holds, second, secondHolds)
        assertNotNull(reg)
        assertTrue(reg.toString(), reg!!.accepted)
        assertEquals(20.0, reg.yawDeg, 0.5)
        // The transform takes second-world points back onto the first: a hold lands on itself.
        val p = DoubleArray(3)
        val h = secondHolds[3]
        reg.transform.apply(h.x.toDouble(), h.y.toDouble(), h.z.toDouble(), p)
        val orig = holds.filter { it.x > -0.3f }[3]
        assertEquals(orig.x.toDouble(), p[0], 0.01); assertEquals(orig.y.toDouble(), p[1], 0.01); assertEquals(orig.z.toDouble(), p[2], 0.01)
        assertTrue("rmse ${reg.rmse}", reg.rmse < 0.015) // point-to-point on 2 cm cells leaves about half a cell
    }

    @Test
    fun noMatchingHoldsMeansNoRegistration() {
        val (cloud, holds) = wall()
        val other = holds.map { HoldPoint(it.x + 5f, it.y, it.z, (it.hue + 180f) % 360f, 0.9f, 0.5f, 0.4f) }
        assertEquals(null, CaptureRegistration.coarse(holds, other))
        assertEquals(null, CaptureRegistration.register(cloud, holds, cloud, other))
    }
}
