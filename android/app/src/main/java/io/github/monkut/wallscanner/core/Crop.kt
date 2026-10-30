package io.github.monkut.wallscanner.core

object Crop {
    /** The largest centred square of a [width] × [height] image: left, top and side in pixels. */
    fun centreSquare(width: Int, height: Int): IntArray {
        val side = minOf(width, height)
        return intArrayOf((width - side) / 2, (height - side) / 2, side)
    }
}
