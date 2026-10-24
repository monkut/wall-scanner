package io.github.monkut.wallscanner.core

import io.github.monkut.wallscanner.io.ClipOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A filmed session at 10 fps: standing, a topped attempt (out of frame mid-climb), a fall, a hop. */
class AttemptDetectorTest {
    private val leg = 0.3f
    private val floor = 0.9f

    /** Feet at [foot], hips a leg length above them, wrists [reach] above the hips; null = nobody in frame. */
    private fun at(tMs: Long, foot: Float?, wrist: Float? = foot?.let { it - 0.45f }, hip: Float? = foot?.let { it - leg }) = PoseSample(tMs, foot, hip, wrist, leg.takeIf { foot != null })

    /** Climbing: the lower hand keeps moving (reaching, shaking out), so it is never held at its highest. */
    private fun climbing(t: Long, foot: Float) = at(t, foot, wrist = foot - 0.45f + 0.08f * kotlin.math.sin(t / 300.0).toFloat())

    private fun session(): List<PoseSample> = (0L until 45_000L step 100).map { t ->
        when {
            t < 5_000 -> at(t, floor)                                             // standing, chalking up
            t < 6_000 -> at(t, floor - 0.3f * (t - 5_000) / 1_000f)               // stepping onto the start
            t in 8_000 until 11_000 -> at(t, null)                                // out of the top of the frame
            t < 13_000 -> climbing(t, 0.5f)
            t < 15_000 -> at(t, 0.45f, wrist = -0.06f)                            // matched on the finish, the highest reach
            t < 18_000 -> at(t, 0.45f + 0.45f * (t - 15_000) / 3_000f)            // down-climb
            t < 25_000 -> at(t, floor)
            t < 26_000 -> at(t, floor - 0.3f * (t - 25_000) / 1_000f)
            t < 32_000 -> climbing(t, 0.55f)
            t < 32_400 -> at(t, 0.55f + 0.35f * (t - 32_000) / 400f)              // off, onto the mat
            t in 34_000 until 36_000 -> at(t, null)                               // walks out of view
            t in 38_000 until 38_500 -> at(t, floor - 0.1f)                       // a hop
            else -> at(t, floor)
        }
    }

    @Test
    fun findsBothAttemptsWithTheirOutcomes() {
        val attempts = AttemptDetector.detect(session(), 45_000)
        assertEquals(2, attempts.size)
        val (top, fall) = attempts
        assertTrue("starts with the pre-roll before the lift: ${top.startMs}", top.startMs in 3_000L..4_000L)
        assertTrue("ends after touch-down plus post-roll: ${top.endMs}", top.endMs in 19_000L..20_500L)
        assertEquals(ClipOutcome.TOP, top.outcome)
        assertTrue("${fall.startMs}", fall.startMs in 23_000L..24_000L)
        assertTrue("${fall.endMs}", fall.endMs in 33_500L..35_000L)
        assertEquals(ClipOutcome.FALL, fall.outcome)
    }

    @Test
    fun aSessionEndingMidClimbEndsTheAttemptAtTheEnd() {
        val cut = session().filter { it.tMs < 12_000 }
        val attempts = AttemptDetector.detect(cut, 12_000)
        assertEquals(1, attempts.size)
        assertEquals(12_000L, attempts[0].endMs)
    }

    @Test
    fun standingOnlyIsNoAttempt() {
        assertTrue(AttemptDetector.detect((0L until 10_000L step 100).map { at(it, floor) }, 10_000).isEmpty())
    }
}
