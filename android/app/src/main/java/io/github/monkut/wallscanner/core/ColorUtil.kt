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
