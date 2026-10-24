package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** Image-based hold shapes must recover a hold's full footprint from a seed that covers only part of it. */
class HoldShapesTest {
    private val px = 0.005f
    private val tan = 0xffC9B496.toInt()

    /** 1 × 1 m orthophoto with a disc of [color] (radius [r]) centred at (0.5, 0.5), lightly shaded left to right. */
    private fun photo(color: Int, r: Float): Orthophoto {
        val n = (1f / px).toInt()
        val pixels = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val u = (x + 0.5f) * px; val v = 1f - (y + 0.5f) * px
            val inside = hypot(u - 0.5f, v - 0.5f) <= r
            val base = if (inside) color else tan
            val shade = 1f - 0.15f * (x.toFloat() / n) // gentle gradient across the wall and the hold
            val rr = ((base shr 16 and 0xff) * shade).toInt(); val gg = ((base shr 8 and 0xff) * shade).toInt(); val bb = ((base and 0xff) * shade).toInt()
            pixels[y * n + x] = (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return Orthophoto(Image(n, n, pixels), 0f, 0f, 1f, 1f, px, FloatArray(n * n))
    }

    private fun area(pts: FloatArray): Float {
        var a = 0f
        val n = pts.size / 2
        for (i in 0 until n) { val j = (i + 1) % n; a += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1] }
        return abs(a) / 2
    }

    private fun seedHold(size: Float, color: Int, id: Int = 1, u: Float = 0.5f, v: Float = 0.5f, voxels: Int = 100): Hold =
        Hold(id, u, v, 0f, u, v, size, size, 0.05f, color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff, voxels)

    private fun refine(holds: List<Hold>, photo: Orthophoto): List<Hold> =
        HoldShapes.refine(holds, photo, ColorPatches.forHolds(photo, null), null)

    @Test
    fun colouredHoldTakesItsWholeColourPatch() {
        val red = 0xffD83A2A.toInt()
        val hold = refine(listOf(seedHold(0.12f, red)), photo(red, 0.15f)).single() // seed 12 cm, disc 30 cm
        val a = area(hold.outline)
        val expected = Math.PI.toFloat() * 0.15f * 0.15f
        assertTrue("area $a vs disc $expected", abs(a - expected) < 0.1f * expected)
    }

    @Test
    fun twoSeedsOnOnePatchMergeIntoOneHold() {
        val purple = 0xff5A3C9E.toInt()
        val holds = listOf(seedHold(0.08f, purple, id = 1, u = 0.42f, voxels = 300), seedHold(0.08f, purple, id = 2, u = 0.58f, voxels = 120))
        val out = refine(holds, photo(purple, 0.15f))
        assertEquals("merged: ${out.map { it.id }}", 1, out.size)
        assertEquals("the hold with more voxels survives", 1, out[0].id)
        val expected = Math.PI.toFloat() * 0.15f * 0.15f
        assertTrue("area ${area(out[0].outline)} vs disc $expected", abs(area(out[0].outline) - expected) < 0.1f * expected)
    }

    @Test
    fun whiteHoldOnTanWallIsFoundByLabDistance() {
        val white = 0xffF2F0EA.toInt()
        val hold = refine(listOf(seedHold(0.10f, white)), photo(white, 0.12f)).single()
        val a = area(hold.outline)
        val expected = Math.PI.toFloat() * 0.12f * 0.12f
        assertTrue("area $a vs disc $expected", abs(a - expected) < 0.2f * expected)
    }

    @Test
    fun growthIsCappedWhenTheWallMatchesTheHold() {
        // Hold the colour of the wall: nothing separates them, so the shape must stay near the seed.
        val hold = refine(listOf(seedHold(0.10f, tan)), photo(tan, 0.12f)).single()
        val a = area(hold.outline)
        assertTrue("area $a should stay near a 10 cm seed", a < Math.PI.toFloat() * 0.09f * 0.09f * 1.2f)
    }
}
