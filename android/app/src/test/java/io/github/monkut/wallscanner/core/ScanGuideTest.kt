package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ScanGuideTest {
    private val up = floatArrayOf(0f, 1f, 0f)
    private val farLimit = 3f

    /** A wall [distance] ahead, turned [yawDeg] about the vertical and [pitchDeg] about the horizontal, on a 20 × 20 window; camera space, −Z forward. */
    private fun wall(distance: Float, yawDeg: Float, pitchDeg: Float = 0f): Pair<FloatArray, Int> {
        val pts = FloatArray(400 * 3)
        val a = Math.toRadians(yawDeg.toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val b = Math.toRadians(pitchDeg.toDouble()); val cb = cos(b).toFloat(); val sb = sin(b).toFloat()
        var n = 0
        for (i in 0 until 20) for (j in 0 until 20) {
            val u = (i - 9.5f) * 0.05f; val v = (j - 9.5f) * 0.05f
            pts[n * 3] = u * ca; pts[n * 3 + 1] = v * cb; pts[n * 3 + 2] = -distance + u * sa + v * sb
            n++
        }
        return pts to n
    }

    @Test
    fun measuresDistanceAndYaw() {
        val (pts, n) = wall(1.5f, 30f)
        val m = ScanGuide.measure(pts, n, up, 0.2f, 0f)!!
        assertEquals(1.5f, m.distance, 0.05f)
        assertEquals(30f, m.facingDeg, 1f)
        assertNull(ScanGuide.judge(m, farLimit))
    }

    @Test
    fun lookingUpAtTheTopRowIsNotTurningAway() {
        val (pts, n) = wall(1.5f, 0f, pitchDeg = 50f)
        assertEquals(0f, ScanGuide.measure(pts, n, up, 0f, 0f)!!.facingDeg, 1f)
    }

    @Test
    fun sweepSpeedAddsTheTurnAtTheWall() {
        val (pts, n) = wall(2f, 0f)
        // panning 0.5 rad/s from 2 m sweeps the window 1 m/s across the wall on top of walking 0.3 m/s
        assertEquals(1.3f, ScanGuide.measure(pts, n, up, 0.3f, 0.5f)!!.speed, 0.05f)
    }

    @Test
    fun cuesInOrderOfWhatMattersMost() {
        assertEquals(ScanGuide.Cue.TOO_CLOSE, ScanGuide.judge(ScanGuide.Measure(0.4f, 10f, 0.2f), farLimit))
        assertEquals(ScanGuide.Cue.TOO_CLOSE, ScanGuide.judge(ScanGuide.Measure(0.4f, 70f, 3f), farLimit))
        assertEquals(ScanGuide.Cue.TOO_FAR, ScanGuide.judge(ScanGuide.Measure(3.4f, 10f, 0.2f), farLimit))
        assertNull(ScanGuide.judge(ScanGuide.Measure(3.4f, 10f, 0.2f), farLimit = 4.25f)) // outdoor depth range
        assertEquals(ScanGuide.Cue.FACE_WALL, ScanGuide.judge(ScanGuide.Measure(2f, 60f, 0.2f), farLimit))
        assertEquals(ScanGuide.Cue.TOO_FAST, ScanGuide.judge(ScanGuide.Measure(2f, 20f, 2.5f), farLimit))
        assertNull(ScanGuide.judge(ScanGuide.Measure(1.5f, 45f, 1.2f), farLimit))
    }

    @Test
    fun tooFewPointsSayNothing() {
        val (pts, _) = wall(1.5f, 0f)
        assertNull(ScanGuide.measure(pts, ScanGuide.MIN_POINTS - 1, up, 0f, 0f))
    }

    @Test
    fun aCueNeedsTwoAgreeingMeasurementsToAppearAndToClear() {
        val state = GuideState()
        assertFalse(state.update(ScanGuide.Cue.TOO_CLOSE))
        assertNull(state.cue)
        assertTrue(state.update(ScanGuide.Cue.TOO_CLOSE))
        assertEquals(ScanGuide.Cue.TOO_CLOSE, state.cue)
        assertFalse(state.update(null)) // a single clean reading does not clear it
        assertFalse(state.update(ScanGuide.Cue.TOO_CLOSE)) // back to the shown cue: pending forgotten
        assertFalse(state.update(null))
        assertTrue(state.update(null))
        assertNull(state.cue)
    }
}
