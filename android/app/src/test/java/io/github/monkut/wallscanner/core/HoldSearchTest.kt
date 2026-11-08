package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** Image-only hold search: coloured patches no hold covers become holds; tape, pads and covered patches do not. */
class HoldSearchTest {
    private val px = 0.005f
    private val tan = 0xffC9B496.toInt()
    private val purple = 0xff5A3C9E.toInt()
    private val frame = WallSurface.single(WallFrame(Plane(0f, 0f, 0f, 0f, 0f, 1f)))
    private val extent = floatArrayOf(0f, 0f, 2f, 2f)

    /** 2 × 2 m tan wall with painted features. */
    private fun photo(paint: (Float, Float) -> Int?): Orthophoto {
        val n = (2f / px).toInt()
        val pixels = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val u = (x + 0.5f) * px; val v = 2f - (y + 0.5f) * px
            pixels[y * n + x] = paint(u, v) ?: tan
        }
        return Orthophoto(Image(n, n, pixels), 0f, 0f, 2f, 2f, px, FloatArray(n * n))
    }

    private fun disc(cu: Float, cv: Float, r: Float, color: Int): (Float, Float) -> Int? = { u, v -> if (hypot(u - cu, v - cv) <= r) color else null }

    private fun patches(photo: Orthophoto) = ColorPatches.forHolds(photo, null)

    @Test
    fun highlightBandDoesNotSplitAHold() {
        // A 30 cm purple disc crossed by a 2 cm white highlight: one patch, one hold, full disc area.
        val white = 0xffF4F2EE.toInt()
        val paint: (Float, Float) -> Int? = { u, v -> if (hypot(u - 1.0f, v - 1.0f) <= 0.15f) (if (abs(v - 1.0f) < 0.01f) white else purple) else null }
        val p = photo(paint)
        val found = HoldSearch.find(emptyList(), patches(p), p, frame, extent)
        assertEquals("one hold, got ${found.map { "(${it.u}, ${it.v}) ${it.width}x${it.tall}" }}", 1, found.size)
        assertTrue("tall ${found[0].tall}", abs(found[0].tall - 0.3f) < 0.02f)
    }

    @Test
    fun aSecondHoldBridgedToAFoundOneIsStillFound() {
        // Two 24 × 12 cm red blocks 2 cm apart (the gap bridging joins their patches); the upper is a depth hold already.
        val red = 0xffC23B3B.toInt()
        val paint: (Float, Float) -> Int? = { u, v -> if (u in 0.88f..1.12f && (v in 1.0f..1.12f || v in 0.86f..0.98f)) red else null }
        val p = photo(paint)
        // SAM drew the upper block a little small: its halo must not tie the lower block to it
        val upper = seedHold(1, 1.0f, 1.06f, red).also { it.outline = Raster.ellipse(1.0f, 1.06f, 0.22f, 0.11f) }
        val found = HoldSearch.find(listOf(upper), patches(p), p, frame, extent)
        assertEquals("the lower block, got ${found.map { "(${it.u}, ${it.v}) ${it.width}x${it.tall}" }}", 1, found.size)
        assertTrue("v ${found[0].v}", abs(found[0].v - 0.92f) < 0.03f)
        // A hold whose patch is only its own halo gets nothing more.
        val halo: (Float, Float) -> Int? = { u, v -> if (hypot(u - 1.0f, v - 1.0f) <= 0.13f) red else null }
        val q = photo(halo)
        val own = seedHold(2, 1.0f, 1.0f, red).also { it.outline = Raster.ellipse(1.0f, 1.0f, 0.24f, 0.24f) }
        assertEquals(0, HoldSearch.find(listOf(own), patches(q), q, frame, extent).size)
    }

    @Test
    fun tapeStripBelowAHoldMarksItAsStart() {
        val paint: (Float, Float) -> Int? = { u, v ->
            when {
                hypot(u - 1.0f, v - 1.5f) <= 0.1f -> purple                  // the hold
                u in 0.99f..1.01f && v in 1.2f..1.36f -> purple              // 2 × 16 cm tape just below it
                hypot(u - 1.0f, v - 0.5f) <= 0.1f -> purple                  // a lower hold of the same colour, no tape
                else -> null
            }
        }
        val photo = photo(paint)
        val holds = listOf(seedHold(1, 1.0f, 1.5f, purple), seedHold(2, 1.0f, 0.5f, purple))
        val marked = TapeMarks.markStarts(holds, photo, null)
        assertEquals(1, marked)
        assertEquals(HoldRole.START, holds[0].role)
        assertEquals(HoldRole.ANY, holds[1].role)
    }

    @Test
    fun darkHoldWithAHueUnlikeTheWallsIsFound() {
        // rgb(26, 42, 26): saturation 0.38, brightness 0.16 — under the hold patch floors, but green on a tan wall.
        val darkGreen = 0xff1A2A1A.toInt()
        val tanWall = WallColor(0xC9, 0xB4, 0x96, 36f, 0.25f, 0.1f, 0.6f, 0.95f)
        val p = photo(disc(1.0f, 1.0f, 0.04f, darkGreen))
        val found = HoldSearch.find(emptyList(), ColorPatches.forHolds(p, tanWall), p, frame, extent)
        assertEquals("one dark hold: ${found.map { "(${it.u}, ${it.v})" }}", 1, found.size)
        assertEquals("not without a wall colour to compare hues with", 0, HoldSearch.find(emptyList(), ColorPatches.forHolds(p, null), p, frame, extent).size)
    }

    @Test
    fun greyTapeFromAPlacardMarksBothHoldsOfATwoHoldStart() {
        val grey = 0xff8C8C8C.toInt()
        val card = 0xffF4F2EE.toInt()
        val green = 0xff5D783C.toInt()
        val paint: (Float, Float) -> Int? = { u, v ->
            when {
                hypot(u - 0.94f, v - 1.45f) <= 0.05f || hypot(u - 1.06f, v - 1.45f) <= 0.05f -> green // the two start holds
                hypot(u - 1.5f, v - 1.45f) <= 0.05f -> green                                         // a hold with no tape
                (u in 0.93f..0.95f || u in 1.05f..1.07f) && v in 1.30f..1.41f -> grey              // 2 × 11 cm grey tape to each hold
                u in 0.97f..1.0f && v in 1.26f..1.28f -> 0xff202020.toInt()                         // grade text on the card
                u in 0.90f..1.10f && v in 1.24f..1.30f -> card                                      // 20 × 6 cm placard
                else -> null
            }
        }
        val photo = photo(paint)
        val holds = listOf(seedHold(1, 0.94f, 1.45f, green, 0.1f), seedHold(2, 1.06f, 1.45f, green, 0.1f), seedHold(3, 1.5f, 1.45f, green, 0.1f))
        val tanWall = WallColor(0xC9, 0xB4, 0x96, 36f, 0.25f, 0.1f, 0.6f, 0.95f)
        val marked = TapeMarks.markStarts(holds, photo, tanWall)
        assertEquals(2, marked)
        assertEquals(listOf(HoldRole.START, HoldRole.START, HoldRole.ANY), holds.map { it.role })
        assertEquals(grey and 0xffffff, holds[0].tape)
        assertEquals(null, holds[2].tape)
    }

    @Test
    fun placardWithAColouredHeaderStillLeadsItsTapeToTheHold() {
        // Oct 29 5:24 PM: a 9 × 8 cm card whose purple header leaves under half of it white, and purple tape
        // from its top that joins the header into one hold-sized colour patch.
        val card = 0xffF4F2EE.toInt()
        val green = 0xff5D783C.toInt()
        val paint: (Float, Float) -> Int? = { u, v ->
            when {
                hypot(u - 1.0f, v - 1.45f) <= 0.05f -> green                                // the start hold
                u in 0.99f..1.01f && v in 1.28f..1.40f -> purple                           // 2 × 12 cm tape up to it
                u in 0.965f..1.035f && v in 1.215f..1.27f -> purple                        // the card's header
                u in 0.985f..1.015f && v in 1.20f..1.215f -> 0xff202020.toInt()            // grade text
                u in 0.955f..1.045f && v in 1.19f..1.28f -> card                           // 9 × 9 cm placard
                else -> null
            }
        }
        val photo = photo(paint)
        val tanWall = WallColor(0xC9, 0xB4, 0x96, 36f, 0.25f, 0.1f, 0.6f, 0.95f)
        val holds = listOf(seedHold(1, 1.0f, 1.45f, green, 0.1f))
        assertEquals(1, TapeMarks.markStarts(holds, photo, tanWall, ColorPatches.forHolds(photo, tanWall), extent))
        assertEquals(HoldRole.START, holds[0].role)
        assertEquals(purple and 0xffffff, holds[0].tape)
    }

    @Test
    fun aCoolTintedCardGetsASecondLookOnAWallThatUsesPlacards() {
        // noborock 1F "20" (2026-11-05): under a warm wall the card reads cool (hue 218°, saturation 0.2), and its silver
        // tape has dark edges, so where the tape leaves the card its tip is edge alone.
        val white = 0xffF4F2EE.toInt(); val green = 0xff5D783C.toInt()
        val coolCard = 0xffB3C3DF.toInt(); val print = 0xff202020.toInt()
        val silver = 0xff92A1B3.toInt(); val edge = 0xff5A5F69.toInt()
        val placardStart: (Float, Float) -> Int? = { u, v ->
            when {
                hypot(u - 1.0f, v - 1.45f) <= 0.05f -> green                       // hold 1
                u in 0.99f..1.01f && v in 1.28f..1.40f -> purple                  // its tape
                u in 0.955f..1.045f && v in 1.19f..1.28f -> white                 // a plain white card
                else -> null
            }
        }
        val coolStart: (Float, Float) -> Int? = { u, v ->
            when {
                hypot(u - 1.5f, v - 1.45f) <= 0.05f -> green                       // hold 2
                u in 1.485f..1.515f && v >= 1.22f && v <= 1.40f -> silver          // silver tape up to it …
                u in 1.485f..1.515f && v >= 1.205f && v < 1.22f -> edge            // … whose tip is its dark edge
                u in 1.48f..1.52f && v in 1.14f..1.18f -> print                    // the grade number
                u in 1.45f..1.55f && v in 1.12f..1.20f -> coolCard                 // a 10 × 8 cm card tinted cool
                else -> null
            }
        }
        val tanWall = WallColor(0xC9, 0xB4, 0x96, 36f, 0.25f, 0.1f, 0.6f, 0.95f)
        fun run(paint: (Float, Float) -> Int?): List<Hold> {
            val photo = photo(paint)
            val holds = listOf(seedHold(1, 1.0f, 1.45f, green, 0.1f), seedHold(2, 1.5f, 1.45f, green, 0.1f))
            TapeMarks.markStarts(holds, photo, tanWall, ColorPatches.forHolds(photo, tanWall), extent)
            return holds
        }
        val both = run { u, v -> placardStart(u, v) ?: coolStart(u, v) }
        assertEquals(HoldRole.START, both[0].role)
        assertEquals("the cool card anchors hold 2 once the wall has shown it uses placards", HoldRole.START, both[1].role)
        assertEquals(silver and 0xffffff, both[1].tape)
        // On a wall with no placard start the cool card is not taken for one (Oct 23 2:00 PM: pale patches read as cards)
        assertEquals(null, run(coolStart)[1].tape)
    }

    private fun seedHold(id: Int, u: Float, v: Float, color: Int, size: Float = 0.2f): Hold =
        Hold(id, u, v, 0f, u, v, size, size, 0.05f, color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff, 100, Raster.ellipse(u, v, size, size))

    @Test
    fun uncoveredPurplePatchBecomesAHoldWithItsShape() {
        val p = photo(disc(1.0f, 1.5f, 0.1f, purple))
        val found = HoldSearch.find(emptyList(), patches(p), p, frame, extent)
        assertEquals(1, found.size)
        val h = found[0]
        assertTrue("centre (${h.u}, ${h.v})", abs(h.u - 1.0f) < 0.01f && abs(h.v - 1.5f) < 0.01f)
        assertTrue("width ${h.width}", abs(h.width - 0.2f) < 0.02f)
        assertEquals("image-only holds carry no voxels", 0, h.voxels)
        assertTrue("outline points", h.outline.size / 2 >= 16)
        assertTrue("named purple", ColorUtil.hueName(h.hue) == "Purple")
    }

    @Test
    fun coveredTapeAndPadPatchesAreIgnored() {
        val paint: (Float, Float) -> Int? = { u, v ->
            when {
                hypot(u - 0.5f, v - 1.0f) <= 0.1f -> purple                       // covered by an existing hold below
                u in 1.2f..1.22f && v in 0.8f..1.0f -> purple                     // 2 cm × 20 cm tape strip
                v < 0.1f && u in 0.5f..1.5f -> purple                             // crash pad along the bottom
                else -> null
            }
        }
        val existing = Hold(1, 0.5f, 1.0f, 0f, 0.5f, 1.0f, 0.2f, 0.2f, 0.05f, 90, 60, 158, 100, Raster.ellipse(0.5f, 1.0f, 0.22f, 0.22f))
        val wallBottomAt20cm = floatArrayOf(0f, 0.2f, 2f, 2f) // the pad lies below the wall surface's lowest edge
        val p = photo(paint)
        val found = HoldSearch.find(listOf(existing), patches(p), p, frame, wallBottomAt20cm)
        assertEquals("nothing new: ${found.map { "(${it.u}, ${it.v}) ${it.width}x${it.tall}" }}", 0, found.size)
    }

    @Test
    fun theSecondSearchWantsBothSidesHoldSized() {
        // A 5.5 × 20 cm painted edge: too wide for tape, a hold at the 4 cm floor, paint at the 6 cm one.
        val p = photo { u, v -> if (u in 1.0f..1.2f && v in 1.0f..1.055f) purple else null }
        assertEquals(1, HoldSearch.find(emptyList(), patches(p), p, frame, extent).size)
        assertEquals(0, HoldSearch.find(emptyList(), patches(p), p, frame, extent, minSide = HoldSearch.SECOND_MIN_SIDE).size)
        // 8 × 20 cm passes both
        val q = photo { u, v -> if (u in 1.0f..1.2f && v in 1.0f..1.08f) purple else null }
        assertEquals(1, HoldSearch.find(emptyList(), patches(q), q, frame, extent, minSide = HoldSearch.SECOND_MIN_SIDE).size)
    }
}
