package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gyro blur estimate, the gyro buffer and the sharp-keyframe choice (#59). */
class SharpKeyframesTest {
    private val fx = 1378f // Pixel 9a 1080p keyframes
    private val width = 1920

    @Test
    fun blurIsRateTimesFocalLengthTimesExposure() {
        // 0.3 rad/s pan for 10 ms: 0.003 rad × 1378 px
        assertEquals(4.134f, Blur.px(floatArrayOf(0.3f, 0f, 0f), 10_000_000L, fx, width), 1e-3f)
        // roll about the optical axis smears the image edge: 0.3 rad/s × 960 px × 10 ms
        assertEquals(2.88f, Blur.px(floatArrayOf(0f, 0f, 0.3f), 10_000_000L, fx, width), 1e-3f)
    }

    @Test
    fun blurIsUnknownWithoutGyroOrExposure() {
        assertTrue(Blur.px(null, 10_000_000L, fx, width).isNaN())
        assertTrue(Blur.px(floatArrayOf(0.3f, 0f, 0f), -1L, fx, width).isNaN())
    }

    @Test
    fun gyroMeanCoversTheExposure() {
        val g = GyroBuffer(capacity = 8)
        for (k in 0 until 20) g.add(k * 5_000_000L, k.toFloat(), 0f, 0f) // 200 Hz, rate = sample number
        // samples 16, 17, 18 (80–90 ms); the buffer holds 12–19
        assertEquals(17f, g.mean(80_000_000L, 90_000_000L)!![0], 1e-6f)
        // no sample inside a 1 ms window: the nearest one
        assertEquals(17f, g.mean(85_500_000L, 86_500_000L)!![0], 1e-6f)
        // long gone from the buffer, or far in the future
        assertNull(g.mean(0L, 1_000_000L))
        assertNull(g.mean(500_000_000L, 510_000_000L))
    }

    @Test
    fun aSharpDueFrameIsTakenAtOnce() {
        val p = SharpPicker<String>(4f, 600L)
        assertNull(p.offer("a", 1f, due = false, nowMs = 0L))
        assertEquals("b" to 2f, p.offer("b", 2f, due = true, nowMs = 100L))
        assertEquals(1, p.takenSharp)
    }

    @Test
    fun aBlurredDueFrameWaitsForASharpOne() {
        val p = SharpPicker<String>(4f, 600L)
        assertNull(p.offer("a", 9f, due = true, nowMs = 0L))
        assertTrue(p.waiting)
        // while waiting every frame is a candidate, due or not
        assertEquals("b" to 3f, p.offer("b", 3f, due = false, nowMs = 100L))
        assertEquals(1, p.takenAfterWait)
    }

    @Test
    fun theSharpestFrameIsTakenWhenTheWaitEnds() {
        val p = SharpPicker<String>(4f, 600L)
        assertNull(p.offer("a", 9f, due = true, nowMs = 0L))
        assertNull(p.offer("b", 6f, due = true, nowMs = 200L))
        assertNull(p.offer("c", 8f, due = true, nowMs = 400L))
        assertEquals("b" to 6f, p.offer("d", 7f, due = true, nowMs = 600L))
        assertEquals(1, p.takenBlurred)
        assertNull(p.flush())
    }

    @Test
    fun aFrameStillWaitingAtFinishIsKept() {
        val p = SharpPicker<String>(4f, 600L)
        assertNull(p.offer("a", 9f, due = true, nowMs = 0L))
        assertEquals("a" to 9f, p.flush())
    }

    @Test
    fun withoutAnEstimateFramesAreTakenAsBefore() {
        val p = SharpPicker<String>(4f, 600L)
        assertEquals("a", p.offer("a", Float.NaN, due = true, nowMs = 0L)!!.first)
        assertEquals(1, p.takenUnknown)
    }

    @Test
    fun theOrthophotoCountsABlurredViewLess() {
        fun kf(blur: Float) = Keyframe("f", Rigid.IDENTITY, fx, fx, 960f, 540f, width, 1080, blur)
        assertEquals(1f, kf(Float.NaN).sharpness, 0f)
        assertEquals(1f, kf(0f).sharpness, 0f)
        assertEquals(0.5f, kf(Tuning.ORTHO_BLUR_REF_PX).sharpness, 1e-6f)
        assertTrue(kf(2f).sharpness > kf(8f).sharpness)
    }
}
