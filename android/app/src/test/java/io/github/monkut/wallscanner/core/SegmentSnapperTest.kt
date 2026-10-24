package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class SegmentSnapperTest {
    /** Pose error typical of an ARCore pause/resume: yaw tilt, depth offset, in-plane shift and a small roll. */
    private val disturbance: Rigid = Rigid.translation(0.12, -0.08, 0.10)
        .compose(Rigid.rotationAbout(0.0, 1.0, 0.0, 8 * PI / 180, 2.0, 1.0, 0.0))
        .compose(Rigid.rotationAbout(0.0, 0.0, 1.0, 1.5 * PI / 180, 2.0, 1.0, 0.0))

    private fun rms(a: VoxelCloud, b: VoxelCloud): Double {
        var sum = 0.0
        for (i in 0 until a.count * 3) { val d = (a.xyz[i] - b.xyz[i]).toDouble(); sum += d * d }
        return sqrt(sum / a.count)
    }

    @Test
    fun rigidComposeAndInverseRoundTrip() {
        val p = floatArrayOf(0.3f, -1.2f, 2.5f)
        val back = disturbance.inverse().apply(disturbance.apply(p))
        for (i in 0 until 3) assertEquals(p[i].toDouble(), back[i].toDouble(), 1e-5)
    }

    @Test
    fun snapsOverlappingSegmentBack() {
        val reference = SyntheticWall.cloud(0f, 1.9f)
        val segment = SyntheticWall.cloud(1.1f, 3f) // 0.8 m overlap holding three holds
        val displaced = disturbance.apply(segment)
        assertTrue("displacement should be large", rms(segment, displaced) > 0.1)

        val result = SegmentSnapper.snap(reference, displaced, SyntheticWall.cameraInFront)
        assertNotNull("snap returned null", result)
        result!!
        assertTrue("snap rejected: $result", result.accepted)
        assertEquals("tilt", 8.0, result.planeAngleDeg, 0.5)
        assertTrue("roll ${result.rollDeg}", result.rollDeg in -2.0..-1.0)

        val snapped = result.transform.apply(displaced)
        val err = rms(segment, snapped)
        assertTrue("residual after snap $err m ($result)", err < 0.02)
    }

    @Test
    fun rejectsSegmentWithoutOverlap() {
        val reference = SyntheticWall.cloud(0f, 1.0f)
        val segment = SyntheticWall.cloud(2.2f, 3f)
        val displaced = Rigid.translation(0.9, 0.0, 0.0).apply(segment)
        val result = SegmentSnapper.snap(reference, displaced, SyntheticWall.cameraInFront)
        assertTrue("should not accept: $result", result == null || !result.accepted)
    }
}
