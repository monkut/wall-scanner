package io.github.monkut.wallscanner.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Live cues for holding the phone while scanning (#36): too close to or far from the wall, not facing it, sweeping
 * too fast. The good noborock scans were made at 1.2–2.3 m from the wall (never under 0.6 m), turned no more
 * than about 25° from facing it (median, about the vertical) and swept at 0.6–1 m/s across the wall; the scan
 * that drifted (2026-11-06 12:25) spent its middle under 0.4 m from the wall, where a plain panel fills the frame
 * and tracking has nothing to hold on to. [measure] fits a plane to the depth frame's central window, [judge]
 * picks the one cue that matters most, and [GuideState] shows a cue only after [CONFIRM] agreeing measurements,
 * so a hold or a hand passing the window does not flash one.
 */
object ScanGuide {
    enum class Cue { TOO_CLOSE, TOO_FAR, FACE_WALL, TOO_FAST }

    /**
     * Median distance to the window's surface in metres; how far the camera is turned from its normal, about the
     * vertical only (looking up at a tall wall's top row is not a fault); and how fast the window sweeps across
     * the wall (the camera's speed plus its turning rate times the distance).
     */
    class Measure(val distance: Float, val facingDeg: Float, val speed: Float) {
        override fun toString(): String = String.format(Locale.US, "distance=%.2f m facing=%.0f° speed=%.2f m/s", distance, facingDeg, speed)
    }

    const val MIN_POINTS = 50
    const val CONFIRM = 2

    /**
     * [points] are camera-space (n × 3; +X right, +Y up, −Z forward) and [up] is gravity's opposite in the same
     * space. Distance is the median depth; the normal comes from the plane through the points (centroid and
     * smallest covariance eigenvector, as PlaneFitter refines); [linearSpeed] in m/s and [angularRate] in rad/s
     * make the sweep speed. Null under [MIN_POINTS] valid points: no surface in the window, nothing to say.
     */
    fun measure(points: FloatArray, n: Int, up: FloatArray, linearSpeed: Float, angularRate: Float): Measure? {
        if (n < MIN_POINTS) return null
        val depths = FloatArray(n) { -points[it * 3 + 2] }
        depths.sort()
        val distance = depths[n / 2]
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in 0 until n) { sx += points[i * 3]; sy += points[i * 3 + 1]; sz += points[i * 3 + 2] }
        val cx = sx / n; val cy = sy / n; val cz = sz / n
        val c = DoubleArray(6)
        for (i in 0 until n) {
            val dx = points[i * 3] - cx; val dy = points[i * 3 + 1] - cy; val dz = points[i * 3 + 2] - cz
            c[0] += dx * dx; c[1] += dx * dy; c[2] += dx * dz; c[3] += dy * dy; c[4] += dy * dz; c[5] += dz * dz
        }
        val normal = smallestEigenvector(c, 0.0, 0.0, 1.0)
        return Measure(distance, yawBetween(normal, up), linearSpeed + angularRate * distance)
    }

    /** Degrees between the camera axis and [normal] once both are flattened onto the plane perpendicular to [up]. */
    private fun yawBetween(normal: DoubleArray, up: FloatArray): Float {
        val ux = up[0].toDouble(); val uy = up[1].toDouble(); val uz = up[2].toDouble()
        val nd = normal[0] * ux + normal[1] * uy + normal[2] * uz
        val nx = normal[0] - nd * ux; val ny = normal[1] - nd * uy; val nz = normal[2] - nd * uz
        val ax = -uz * ux; val ay = -uz * uy; val az = 1 - uz * uz // the camera axis (0, 0, 1) flattened
        val nl = sqrt(nx * nx + ny * ny + nz * nz); val al = sqrt(ax * ax + ay * ay + az * az)
        if (nl < 1e-6 || al < 1e-6) return 0f // looking straight up or down the wall: no yaw to speak of
        return Math.toDegrees(acos((abs(nx * ax + ny * ay + nz * az) / (nl * al)).coerceIn(0.0, 1.0))).toFloat()
    }

    /** The cue that matters most, or null when the phone is held well; [farLimit] is the distance beyond which depth goes to waste. */
    fun judge(m: Measure, farLimit: Float): Cue? = when {
        m.distance < Tuning.GUIDE_MIN_DISTANCE -> Cue.TOO_CLOSE
        m.distance > farLimit -> Cue.TOO_FAR
        m.facingDeg > Tuning.GUIDE_MAX_FACING_DEG -> Cue.FACE_WALL
        m.speed > Tuning.GUIDE_MAX_SPEED -> Cue.TOO_FAST
        else -> null
    }
}

/** The cue on screen: it changes only after [ScanGuide.CONFIRM] measurements in a row agree on the new one. */
class GuideState {
    var cue: ScanGuide.Cue? = null
        private set
    private var pending: ScanGuide.Cue? = null
    private var agree = 0

    /** Feeds one judgement; true when the shown cue changed. */
    fun update(next: ScanGuide.Cue?): Boolean {
        if (next == cue) { pending = null; agree = 0; return false }
        if (next == pending) agree++ else { pending = next; agree = 1 }
        if (agree < ScanGuide.CONFIRM) return false
        cue = next; pending = null; agree = 0
        return true
    }
}
