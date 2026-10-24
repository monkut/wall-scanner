package io.github.monkut.wallscanner.core

import kotlin.math.max
import kotlin.math.min

object ColorUtil {
    /** Returns [hue in degrees 0..360, saturation 0..1, value 0..1]. */
    fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val mx = max(rf, max(gf, bf))
        val mn = min(rf, min(gf, bf))
        val delta = mx - mn
        var h = when {
            delta < 1e-6f -> 0f
            mx == rf -> 60f * (((gf - bf) / delta) % 6f)
            mx == gf -> 60f * ((bf - rf) / delta + 2f)
            else -> 60f * ((rf - gf) / delta + 4f)
        }
        if (h < 0f) h += 360f
        val s = if (mx < 1e-6f) 0f else delta / mx
        return floatArrayOf(h, s, mx)
    }

    /** sRGB → CIE Lab (D65): [L 0..100, a, b], as OpenCV's float conversion gives it. */
    fun rgbToLab(r: Int, g: Int, b: Int): FloatArray {
        fun lin(c: Int): Float { val v = c / 255f; return if (v <= 0.04045f) v / 12.92f else Math.pow((v + 0.055) / 1.055, 2.4).toFloat() }
        fun f(t: Float): Float = if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
        val rl = lin(r); val gl = lin(g); val bl = lin(b)
        val x = (0.412453f * rl + 0.357580f * gl + 0.180423f * bl) / 0.950456f
        val y = 0.212671f * rl + 0.715160f * gl + 0.072169f * bl
        val z = (0.019334f * rl + 0.119193f * gl + 0.950227f * bl) / 1.088754f
        val l = if (y > 0.008856f) 116f * Math.cbrt(y.toDouble()).toFloat() - 16f else 903.3f * y
        return floatArrayOf(l, 500f * (f(x) - f(y)), 200f * (f(y) - f(z)))
    }

    /** Shortest angular distance between two hues in degrees, 0..180. */
    fun hueDistance(a: Float, b: Float): Float = kotlin.math.abs(((a - b) % 360f + 540f) % 360f - 180f)

    fun hueName(hue: Float): String = when {
        hue < 15f || hue >= 345f -> "Red"
        hue < 45f -> "Orange"
        hue < 70f -> "Yellow"
        hue < 170f -> "Green"
        hue < 200f -> "Cyan"
        hue < 245f -> "Blue" // gym purples sit at 250–260°
        hue < 300f -> "Purple"
        else -> "Pink"
    }
}
