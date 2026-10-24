package io.github.monkut.wallscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

/** SAM's input, prompt box and mask reading, and which masks replace a hold's outline. */
class SamRefineTest {
    private val px = 0.005f

    private fun disc(cx: Int, cy: Int, r: Float, w: Int, h: Int = w): IntArray =
        (0 until w * h).filter { hypot(it % w + 0.5f - cx, it / w + 0.5f - cy) <= r }.toIntArray()

    @Test
    fun inputIsTheResizedPhotoNormalisedWithZeroPadding() {
        val image = Image(100, 50, IntArray(100 * 50) { 0xffC86432.toInt() }) // r 200, g 100, b 50
        val input = SamRefine.input(image)
        val plane = SamRefine.SIZE * SamRefine.SIZE
        assertEquals(3 * plane, input.size)
        assertEquals((200 - 123.675f) / 58.395f, input[0], 1e-4f)
        assertEquals((100 - 116.28f) / 57.12f, input[plane], 1e-4f)
        assertEquals((50 - 103.53f) / 57.375f, input[2 * plane + 511 * SamRefine.SIZE + 1023], 1e-4f) // last photo row
        assertEquals(0f, input[512 * SamRefine.SIZE], 0f)                                               // first padding row
        assertEquals(10.24f, SamRefine.scale(image), 1e-6f)
    }

    @Test
    fun boxIsTheOutlineBoundsInPhotoPixels() {
        val photo = Orthophoto(Image(200, 200, IntArray(200 * 200)), 0f, 0f, 1f, 1f, px, FloatArray(200 * 200))
        val hold = Hold(1, 0f, 0f, 0f, 0.3f, 0.6f, 0.1f, 0.1f, 0f, 0, 0, 0, 0, floatArrayOf(0.25f, 0.55f, 0.35f, 0.55f, 0.35f, 0.65f, 0.25f, 0.65f))
        assertArrayEquals(floatArrayOf(50f, 70f, 70f, 90f), SamRefine.box(hold, photo), 1e-3f)
    }

    @Test
    fun maskIsReadFromTheLogitsInsideTheGrownBox() {
        val w = 200
        val scale = SamRefine.SIZE.toFloat() / w // 5.12: one logit cell is 0.8 photo pixels
        val k = scale * SamRefine.MASK / SamRefine.SIZE
        // A disc of radius 16 photo px at (60, 80) in logit space: +6 inside, −6 outside.
        val logits = FloatArray(SamRefine.MASK * SamRefine.MASK) { i ->
            val x = (i % SamRefine.MASK + 0.5f) / k; val y = (i / SamRefine.MASK + 0.5f) / k
            if (hypot(x - 60f, y - 80f) <= 16f) 6f else -6f
        }
        val box = floatArrayOf(44f, 64f, 76f, 96f)
        val mask = SamRefine.maskPixels(logits, box, scale, w, w)
        assertEquals(PI * 16 * 16, mask.size.toDouble(), 30.0)
        assertTrue(mask.all { hypot(it % w + 0.5f - 60f, it / w + 0.5f - 80f) <= 17f })
        // Everything positive: only the box grown by a quarter of its size on each side.
        val all = SamRefine.maskPixels(FloatArray(logits.size) { 1f }, box, scale, w, w)
        assertEquals(49 * 49, all.size) // x 36..84, y 56..104
    }

    @Test
    fun maskPixelCentresMapIntoTheLogitGrid() {
        val w = 200
        val scale = SamRefine.SIZE.toFloat() / w // photo pixel x has its centre at logit column (x + 0.5) · 1.28 − 0.5
        // Logits rise by 1 per column and cross 0 at column 64.4: photo pixel 50 (column 64.14) is out, 51 (65.42) in.
        val logits = FloatArray(SamRefine.MASK * SamRefine.MASK) { i -> i % SamRefine.MASK - 64.4f }
        val mask = SamRefine.maskPixels(logits, floatArrayOf(40f, 40f, 60f, 60f), scale, w, w)
        assertEquals(51, mask.minOf { it % w })
    }

    @Test
    fun acceptKeepsThePartOnTheOldOutlineAndFillsItsHoles() {
        val w = 200
        val ring = disc(60, 80, 16f, w).filter { hypot(it % w + 0.5f - 60f, it / w + 0.5f - 80f) > 5f }
        val bigger = disc(150, 150, 30f, w) // a neighbour SAM also lit up, larger than the hold
        val old = disc(60, 80, 20f, w)      // the old outline: the hold plus its shadow
        val kept = SamRefine.accept((ring + bigger.toList()).toIntArray(), old, 0.9f, w)!!
        assertEquals(disc(60, 80, 16f, w).size, kept.size)
        assertTrue(kept.all { hypot(it % w + 0.5f - 60f, it / w + 0.5f - 80f) <= 16f })
    }

    @Test
    fun acceptKeepsTheOldOutlineWhenSamIsUnsureOrDisagrees() {
        val w = 200
        val old = disc(60, 80, 16f, w)
        assertNull(SamRefine.accept(old, old, 0.5f, w))                     // low predicted IoU
        assertNull(SamRefine.accept(disc(150, 150, 16f, w), old, 0.9f, w))   // another object
        assertNull(SamRefine.accept(disc(60, 80, 7.5f, w), old, 0.9f, w))    // a part: IoU 0.22 passes, area 0.22 of the old does not
        assertEquals(disc(60, 80, 20f, w).size, SamRefine.accept(disc(60, 80, 20f, w), old, 0.9f, w)?.size) // a partial outline grows to the whole hold
    }

    @Test
    fun aMaskTakingFromAnotherHoldIsRefusedUnlessItIsAPieceOfThisOne() {
        val w = 200
        val left = disc(50, 80, 12f, w); val right = disc(74, 80, 12f, w) // two halves of a hold split upstream
        val owner = IntArray(w * w) { -1 }
        for (i in left) owner[i] = 0
        for (i in right) owner[i] = 1
        val whole = (left + right.toList()).distinct().toIntArray()
        assertTrue(SamRefine.takesFromOthers(whole, left, owner, intArrayOf(0, 1), 0))
        assertTrue(!SamRefine.takesFromOthers(whole, left, owner, intArrayOf(0, 0), 0)) // the right half merged into it
        assertTrue(!SamRefine.takesFromOthers(left, left, owner, intArrayOf(0, 1), 0))
    }

    @Test
    fun piecesInsideALargerMaskMergeIntoIt() {
        val w = 200
        val whole = disc(60, 80, 20f, w)            // SAM's mask from the larger piece: the whole hold
        val piece = disc(66, 80, 10f, w)            // from the smaller piece: itself, inside the whole
        val neighbour = disc(150, 80, 12f, w)       // another hold
        val touching = disc(86, 80, 10f, w)         // a hold touching the whole one, mostly outside it
        assertArrayEquals(intArrayOf(-1, 0, -1, -1, -1), SamRefine.mergeInto(listOf(whole, piece, neighbour, touching, null)))
    }

    @Test
    fun contrastSeparatesAHoldFromAPatchOfWall() {
        val w = 100
        val wall = 0xffC9B496.toInt()
        val hold = disc(50, 50, 10f, w)
        val inHold = hold.toHashSet()
        val image = Image(w, w, IntArray(w * w) { if (it in inHold) 0xff3A6EA5.toInt() else wall })
        assertTrue(SamRefine.contrast(hold, image, BooleanArray(w * w)) > 30f)
        val speckled = Image(w, w, IntArray(w * w) { if (it in inHold && it % 7 == 0) 0xff6B5E4E.toInt() else wall }) // bolt holes in plywood
        assertTrue(SamRefine.contrast(hold, speckled, BooleanArray(w * w)) < SamRefine.MIN_CONTRAST)
    }
}
