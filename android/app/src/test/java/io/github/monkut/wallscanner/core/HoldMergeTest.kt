package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Model masks split a hold that joins two of them and fill gaps; edge and bare-wall holds stay only when coloured or seen by the model. */
class HoldMergeTest {
    private val px = 0.005f
    private val tan = 0xffC9B496.toInt()
    private val red = 0xffC23B3B.toInt()
    private val grey = 0xff808080.toInt()
    private val frame = WallFrame(Plane(0f, 0f, 0f, 0f, 0f, 1f))
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

    private fun hold(id: Int, u: Float, v: Float, width: Float, tall: Float, voxels: Int = 0): Hold =
        Hold(id, u, v, 0f, u, v, width, tall, 0.05f, 194, 59, 59, voxels, Raster.ellipse(u, v, width, tall))

    @Test
    fun aHoldJoiningTwoMasksBecomesThem() {
        val p = photo { _, _ -> null }
        val blob = hold(3, 0.5f, 0.5f, 0.5f, 0.24f, voxels = 400).also { it.role = HoldRole.START; it.tape = 0x808080 }
        val big = hold(10, 0.38f, 0.5f, 0.2f, 0.16f); val small = hold(11, 0.62f, 0.5f, 0.16f, 0.14f)
        val merged = HoldMerge.withModel(listOf(blob), listOf(big, small, hold(12, 0.38f, 0.5f, 0.18f, 0.14f)), p, frame)
        assertEquals("the duplicate of the big mask is dropped", listOf(3, 11), merged.holds.map { it.id })
        assertEquals(1, merged.split)
        val first = merged.holds[0]
        assertEquals(0.38f, first.u, 1e-6f)
        assertEquals(HoldRole.START, first.role)
        assertEquals(0x808080, first.tape)
        assertEquals(400, first.voxels)
    }

    @Test
    fun masksAreAddedOnlyWhereNoHoldIs() {
        val p = photo { _, _ -> null }
        val existing = hold(1, 0.3f, 0.3f, 0.2f, 0.2f)
        val merged = HoldMerge.withModel(listOf(existing), listOf(hold(5, 0.31f, 0.3f, 0.18f, 0.18f), hold(6, 0.7f, 0.7f, 0.1f, 0.1f)), p, frame)
        assertEquals(listOf(1, 6), merged.holds.map { it.id })
        assertEquals(0, merged.split)
        assertEquals(1, merged.added)
    }

    @Test
    fun edgeHoldsNeedColourOrTheModel() {
        // The wall extent leaves a 10 cm margin of photo on every side, as the orthophoto does.
        val extent = floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)
        val p = photo { u, v -> if (hypot(u - 0.08f, v - 0.5f) <= 0.04f) red else if (u > 0.85f || v < 0.12f || hypot(u - 0.5f, v - 0.5f) <= 0.05f) grey else null }
        val redAtEdge = hold(1, 0.08f, 0.5f, 0.08f, 0.08f)
        val greyAtEdge = hold(2, 0.92f, 0.5f, 0.1f, 0.1f)
        val greySeen = hold(3, 0.5f, 0.06f, 0.1f, 0.08f)
        val inside = hold(4, 0.5f, 0.5f, 0.1f, 0.1f)
        val kept = HoldMerge.prune(listOf(redAtEdge, greyAtEdge, greySeen, inside), listOf(hold(9, 0.5f, 0.06f, 0.1f, 0.08f)), emptyList(), p, ColorPatches.forHolds(p, wall), wall, extent)
        assertEquals(listOf(1, 3, 4), kept.map { it.id })
        assertEquals("a hold wholly off the photo is past the edge", 0, HoldMerge.prune(listOf(hold(5, -0.2f, 0.5f, 0.08f, 0.08f, voxels = 50)), emptyList(), emptyList(), p, ColorPatches.forHolds(p, wall), wall, extent).size)
        assertTrue("nothing judged without a wall colour", HoldMerge.prune(listOf(greyAtEdge), emptyList(), emptyList(), p, ColorPatches.forHolds(p, null), null, extent).size == 1)
    }

    @Test
    fun anOutlineOfBareWallIsDropped() {
        val extent = floatArrayOf(0f, 0f, 1f, 1f)
        val p = photo { u, v -> if (hypot(u - 0.7f, v - 0.5f) <= 0.05f) grey else null }
        val bareWall = hold(1, 0.3f, 0.5f, 0.15f, 0.15f, voxels = 100)
        val greyHold = hold(2, 0.7f, 0.5f, 0.1f, 0.1f, voxels = 100)
        val kept = HoldMerge.prune(listOf(bareWall, greyHold), emptyList(), emptyList(), p, ColorPatches.forHolds(p, wall), wall, extent)
        assertEquals("a grey hold is not the wall's colour", listOf(2), kept.map { it.id })
        assertEquals("the model vouches for wood-coloured holds", 2, HoldMerge.prune(listOf(bareWall, greyHold), listOf(hold(9, 0.3f, 0.5f, 0.15f, 0.15f)), emptyList(), p, ColorPatches.forHolds(p, wall), wall, extent).size)
    }

    @Test
    fun masksSharingALongBoundaryStayOneHold() {
        // One blue hold the model cut in two at a keyframe seam: a narrow lobe touching the body along a vertical line.
        val p = photo { _, _ -> null }
        val hold = hold(3, 0.5f, 0.5f, 0.3f, 0.2f, voxels = 200).also { it.role = HoldRole.START }
        val left = Hold(10, 0.47f, 0.5f, 0f, 0.47f, 0.5f, 0.06f, 0.12f, 0.02f, 40, 90, 200, 0, floatArrayOf(0.44f, 0.44f, 0.5f, 0.44f, 0.5f, 0.56f, 0.44f, 0.56f))
        val right = Hold(11, 0.55f, 0.5f, 0f, 0.55f, 0.5f, 0.1f, 0.12f, 0.02f, 40, 90, 200, 0, floatArrayOf(0.5f, 0.44f, 0.6f, 0.44f, 0.6f, 0.56f, 0.5f, 0.56f))
        val merged = HoldMerge.withModel(listOf(hold), listOf(left, right), p, frame)
        assertEquals(0, merged.split)
        assertEquals(listOf(3), merged.holds.map { it.id })
        assertEquals(HoldRole.START, merged.holds[0].role)
    }

    @Test
    fun aHoldTheModelCallsTapeIsDropped() {
        val extent = floatArrayOf(0f, 0f, 1f, 1f)
        val p = photo { u, v -> if (u in 0.48f..0.52f && v in 0.3f..0.5f) red else null }
        val strip = hold(1, 0.5f, 0.4f, 0.07f, 0.22f) // the photo search's outline of a red tape strip
        val kept = HoldMerge.prune(listOf(strip), listOf(hold(8, 0.5f, 0.4f, 0.07f, 0.22f)), listOf(hold(9, 0.5f, 0.4f, 0.06f, 0.22f)), p, ColorPatches.forHolds(p, wall), wall, extent)
        assertEquals(0, kept.size)
    }
}
