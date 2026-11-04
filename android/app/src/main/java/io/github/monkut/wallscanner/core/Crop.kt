package io.github.monkut.wallscanner.core

import kotlin.math.ceil
import kotlin.math.sqrt

object Crop {
    /**
     * How much to shrink a [width] × [height] image while decoding it for a [side] square: enough that its short side
     * stays at least [side] (no detail is thrown away for normal photos), and at least enough that no more than
     * [maxPixels] are decoded (a 50 000 × 1 000 panorama would otherwise need 200 MB).
     */
    fun sampleSize(width: Int, height: Int, side: Int, maxPixels: Long): Int {
        val forSide = minOf(width, height) / side
        val forMemory = ceil(sqrt(width.toDouble() * height / maxPixels)).toInt()
        return maxOf(1, forSide, forMemory)
    }

    /** The largest centred square of a [width] × [height] image: left, top and side in pixels. */
    fun centreSquare(width: Int, height: Int): IntArray {
        val side = minOf(width, height)
        return intArrayOf((width - side) / 2, (height - side) / 2, side)
    }
}
