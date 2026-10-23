package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Clahe matches tools/holds_model/clahe.py, which the hold model was trained through. */
class ClaheTest {
    @Test
    fun matchesThePythonReference() {
        // The same 40 × 30 pattern as the Python check; expected values from clahe.equalize.
        val w = 40; val h = 30
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            (0xff shl 24) or (((x * 37 + y * 11) % 256) shl 16) or (((x * 7 + y * 53) % 256) shl 8) or ((x * x + y * 3) % 256)
        }
        val out = Clahe.equalize(Image(w, h, pixels)).pixels
        fun rgb(x: Int, y: Int) = out[y * w + x].let { listOf(it shr 16 and 0xff, it shr 8 and 0xff, it and 0xff) }
        assertEquals(listOf(17, 17, 17), rgb(0, 0))
        assertEquals(listOf(255, 252, 92), rgb(5, 3))
        assertEquals(listOf(77, 0, 73), rgb(17, 22))
        assertEquals(listOf(178, 0, 24), rgb(39, 29))
        assertEquals(listOf(139, 169, 191), rgb(20, 15))
        assertEquals(453002L, out.sumOf { ((it shr 16 and 0xff) + (it shr 8 and 0xff) + (it and 0xff)).toLong() })
        assertEquals(1617184L, out.sumOf { ((it shr 16 and 0xff) + 3 * (it shr 8 and 0xff) + 7 * (it and 0xff)).toLong() })
    }
}
