package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** A depth blob covering holds of two hues splits into one hold per hue; a highlight of another hue does not split a hold. */
class HoldSplitTest {
    private val px = 0.005f
    private val tan = 0xffC9B496.toInt()
    private val red = 0xffC23B3B.toInt()
    private val green = 0xff3E8A3A.toInt()
    private val frame = WallSurface.single(WallFrame(Plane(0f, 0f, 0f, 0f, 0f, 1f)))
    private val wall = WallColor(0xC9, 0xB4, 0x96, 36f, 0.25f, 0.1f, 0.6f, 0.95f)

    private fun photo(paint: (Float, Float) -> Int?): Orthophoto {
        val n = (1f / px).toInt()
        val pixels = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val u = (x + 0.5f) * px; val v = 1f - (y + 0.5f) * px
            pixels[y * n + x] = paint(u, v) ?: tan
        }
        return Orthophoto(Image(n, n, pixels), 0f, 0f, 1f, 1f, px, FloatArray(n * n))
    }

    /** One depth hold whose ellipse covers (0.3..0.7, 0.4..0.6). */
    private fun blob(): Hold = Hold(7, 0.5f, 0.5f, 0f, 0.5f, 0.5f, 0.4f, 0.2f, 0.3f, 120, 90, 80, 500, Raster.ellipse(0.5f, 0.5f, 0.4f, 0.2f)).also { it.role = HoldRole.FINISH }

    @Test
    fun twoHuesUnderOneBlobBecomeTwoHolds() {
        val p = photo { u, v -> if (hypot(u - 0.4f, v - 0.5f) <= 0.06f) red else if (hypot(u - 0.6f, v - 0.5f) <= 0.045f) green else null }
        val out = HoldSplit.byColor(listOf(blob()), p, ColorPatches.forHolds(p, wall), frame, wall)
        assertEquals(2, out.size)
        val first = out[0]; val second = out[1]
        assertEquals("the largest part keeps the id", 7, first.id)
        assertEquals(HoldRole.FINISH, first.role)
        assertEquals(500, first.voxels)
        assertTrue("red centre (${first.u}, ${first.v})", abs(first.u - 0.4f) < 0.01f && abs(first.v - 0.5f) < 0.01f)
        assertEquals("the other part is image-only", 0, second.voxels)
        assertTrue("green centre (${second.u}, ${second.v})", abs(second.u - 0.6f) < 0.01f)
        assertTrue("green outline ${second.outline.size / 2} pts", second.outline.size / 2 >= 12)
        assertEquals("Green", ColorUtil.hueName(second.hue))
    }

    @Test
    fun aSmallHighlightOfAnotherHueDoesNotSplit() {
        val p = photo { u, v -> if (hypot(u - 0.5f, v - 0.5f) <= 0.08f) (if (hypot(u - 0.53f, v - 0.52f) <= 0.012f) green else red) else null }
        val out = HoldSplit.byColor(listOf(blob()), p, ColorPatches.forHolds(p, wall), frame, wall)
        assertEquals(1, out.size)
        assertEquals(7, out[0].id)
    }
}
