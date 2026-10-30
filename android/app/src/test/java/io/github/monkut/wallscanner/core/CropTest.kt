package io.github.monkut.wallscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CropTest {
    @Test
    fun centreSquareOfLandscapePortraitAndSquare() {
        assertArrayEquals(intArrayOf(1000, 0, 3000), Crop.centreSquare(5000, 3000))
        assertArrayEquals(intArrayOf(0, 500, 3000), Crop.centreSquare(3000, 4000))
        assertArrayEquals(intArrayOf(0, 0, 512), Crop.centreSquare(512, 512))
    }
}
