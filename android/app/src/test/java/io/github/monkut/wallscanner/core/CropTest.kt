package io.github.monkut.wallscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropTest {
    @Test
    fun centreSquareOfLandscapePortraitAndSquare() {
        assertArrayEquals(intArrayOf(1000, 0, 3000), Crop.centreSquare(5000, 3000))
        assertArrayEquals(intArrayOf(0, 500, 3000), Crop.centreSquare(3000, 4000))
        assertArrayEquals(intArrayOf(0, 0, 512), Crop.centreSquare(512, 512))
    }

    @Test
    fun sampleSizeKeepsPhotosSharpAndBoundsMemory() {
        val max = 4_000_000L
        assertEquals("12 MP photo: short side 3000 → 600", 5, Crop.sampleSize(4000, 3000, 512, max))
        assertEquals("small image is not shrunk", 1, Crop.sampleSize(400, 300, 512, max))
        assertEquals("200 MP photo", 23, Crop.sampleSize(16320, 12240, 512, max))
        for ((w, h) in listOf(50_000 to 1_000, 30_000 to 1_500, 16320 to 12240, 4000 to 3000)) {
            val s = Crop.sampleSize(w, h, 512, max)
            assertTrue("$w x $h decodes to at most $max pixels", (w.toLong() / s) * (h.toLong() / s) <= max)
        }
    }
}
