package io.github.monkut.wallscanner.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Colour of the wall surface (voxels within [Tuning.WALL_COLOR_BAND] of the plane) and a test for colours
 * that match it. Depth noise puts wall-coloured voxels into every hold's skirt, and on a painted or plywood
 * wall those voxels are saturated enough to pass as a hold colour of their own; both the hold colour estimate
 * and the route grouping leave wall-coloured voxels and holds out.
 *
 * The tolerances follow the wall's own spread: saturation within its interquartile range (at least
 * [Tuning.WALL_SAT_TOL]) of the median, value between its 10th and 90th percentiles widened by
 * [Tuning.WALL_VAL_TOL], hue within [Tuning.WALL_HUE_TOL] of the wall's hue. That hue is the peak of the
 * saturation-weighted hue histogram, refined by the mean direction of the voxels near the peak: the plane's
 * band also holds every hold's skirt, and on a busy wall their colours pull a plain mean far off (purple and
 * red holds turned tan plywood at 35° into 8°). Hue is ignored for a grey wall (median saturation under
 * [Tuning.WALL_GREY_SAT]).
 */
class WallColor(
    /** Mean surface colour, for drawing the wall. */
    val r: Int, val g: Int, val b: Int,
    val hue: Float, val sat: Float, val satTol: Float, val valLo: Float, val valHi: Float,
) {

    fun matches(r: Int, g: Int, b: Int): Boolean = matches(ColorUtil.rgbToHsv(r, g, b))

    /** True when [c] (hue, saturation, value) lies within the wall tolerances. */
    fun matches(c: FloatArray): Boolean = matches(c[0], c[1], c[2])

    fun matches(h: Float, s: Float, v: Float): Boolean {
        if (v < valLo || v > valHi) return false
        if (sat < Tuning.WALL_GREY_SAT) return s <= sat + satTol
        if (abs(s - sat) > satTol) return false
        val dh = abs(((h - hue) % 360f + 540f) % 360f - 180f)
        return dh <= Tuning.WALL_HUE_TOL
    }

    override fun toString(): String =
        "rgb($r, $g, $b) hue=${"%.0f".format(hue)}±${"%.0f".format(Tuning.WALL_HUE_TOL)} sat=${"%.2f".format(sat)}±${"%.2f".format(satTol)} " +
            "val=${"%.2f".format(valLo)}..${"%.2f".format(valHi)}"

    companion object {
        private const val HUE_BINS = 36
        private const val PEAK_SPAN = 20f // voxels this close to the histogram peak refine the hue

        /** Null when fewer than 100 voxels lie on the plane. */
        fun of(cloud: VoxelCloud, frame: WallFrame): WallColor? {
            val xyz = cloud.xyz; val rgb = cloud.rgb
            var n = 0
            for (i in 0 until cloud.count) if (abs(frame.d(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])) <= Tuning.WALL_COLOR_BAND) n++
            if (n < 100) return null
            val sats = FloatArray(n); val vals = FloatArray(n); val hues = FloatArray(n)
            val hist = FloatArray(HUE_BINS)
            var sr = 0L; var sg = 0L; var sb = 0L
            var k = 0
            for (i in 0 until cloud.count) {
                if (abs(frame.d(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])) > Tuning.WALL_COLOR_BAND) continue
                val r = rgb[i * 3].toInt() and 0xff; val g = rgb[i * 3 + 1].toInt() and 0xff; val b = rgb[i * 3 + 2].toInt() and 0xff
                sr += r; sg += g; sb += b
                val c = ColorUtil.rgbToHsv(r, g, b)
                // Saturation-weighted so grey voxels do not pull the hue around.
                hist[(c[0] / (360f / HUE_BINS)).toInt().coerceIn(0, HUE_BINS - 1)] += c[1]
                hues[k] = c[0]; sats[k] = c[1]; vals[k] = c[2]
                k++
            }
            val peakBin = (0 until HUE_BINS).maxBy { b -> hist[(b + HUE_BINS - 1) % HUE_BINS] + 2 * hist[b] + hist[(b + 1) % HUE_BINS] }
            val peak = (peakBin + 0.5f) * (360f / HUE_BINS)
            var sinSum = 0.0; var cosSum = 0.0
            for (j in 0 until n) {
                if (ColorUtil.hueDistance(hues[j], peak) > PEAK_SPAN) continue
                val rad = Math.toRadians(hues[j].toDouble())
                sinSum += sin(rad) * sats[j]; cosSum += cos(rad) * sats[j]
            }
            sats.sort(); vals.sort()
            var hue = Math.toDegrees(atan2(sinSum, cosSum)).toFloat()
            if (hue < 0f) hue += 360f
            val sat = sats[n / 2]
            val iqr = sats[n * 3 / 4] - sats[n / 4]
            return WallColor(
                (sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt(),
                hue, sat, max(Tuning.WALL_SAT_TOL, iqr),
                vals[n / 10] - Tuning.WALL_VAL_TOL, vals[n * 9 / 10] + Tuning.WALL_VAL_TOL,
            )
        }
    }
}
