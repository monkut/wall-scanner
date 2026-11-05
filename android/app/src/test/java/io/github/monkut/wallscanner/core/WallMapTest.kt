package io.github.monkut.wallscanner.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sheets tile from the bottom-left corner and are cut to the wall; holes repeat per sheet from its corner. */
class WallMapTest {
    private fun map(width: Float, height: Float, pattern: TNutPattern = TNutPattern.SQUARE) =
        WallMap(width, height, 0.91f, 1.82f, 0.2f, pattern, 0.1f, 0.1f)

    @Test
    fun oneStandingSheetHoldsAGridFromItsCorner() {
        val m = map(0.91f, 1.82f)
        assertEquals(1, m.columns)
        assertEquals(1, m.rows)
        val holes = m.holes()
        assertEquals(5 * 9, holes.size) // u = 0.1 … 0.9, v = 0.1 … 1.7
        assertEquals(0.1f, holes[0][0], 1e-6f)
        assertEquals(0.1f, holes[0][1], 1e-6f)
        assertEquals(0.9f, holes[4][0], 1e-6f)
    }

    @Test
    fun theSecondSheetRestartsTheGridFromItsOwnCorner() {
        val m = map(1.82f, 1.82f)
        assertEquals(2, m.columns)
        val holes = m.holes()
        assertEquals(2 * 45, holes.size)
        assertTrue(holes.any { kotlin.math.abs(it[0] - 1.01f) < 1e-6f && kotlin.math.abs(it[1] - 0.1f) < 1e-6f })
    }

    @Test
    fun aPartialSheetIsCutToTheWall() {
        val m = map(1.5f, 3f)
        assertEquals(2, m.columns)
        assertEquals(2, m.rows)
        val sheets = m.sheets()
        assertEquals(4, sheets.size)
        assertEquals(1.5f, sheets[1].u1, 1e-6f)
        assertEquals(3f, sheets[3].v1, 1e-6f)
        assertTrue(m.holes().all { it[0] < 1.5f && it[1] < 3f })
    }

    @Test
    fun staggeredShiftsEveryOtherRowByHalfAPitch() {
        val holes = map(0.91f, 1.82f, TNutPattern.STAGGERED).holes()
        val row1 = holes.filter { kotlin.math.abs(it[1] - 0.3f) < 1e-6f }
        assertEquals(0.2f, row1[0][0], 1e-6f)
        assertEquals(4, row1.size) // 0.2, 0.4, 0.6, 0.8
    }

    @Test
    fun roundTripsThroughJsonToTheMillimetre() {
        val m = WallMap(4.2f, 3.65f, 1.82f, 0.91f, 0.203f, TNutPattern.STAGGERED, 0.125f, 0.05f)
        val back = WallMap.fromJson(JSONObject(m.toJson().toString()))
        assertEquals(4.2f, back.width, 1e-6f)
        assertEquals(3.65f, back.height, 1e-6f)
        assertEquals(1.82f, back.sheetWidth, 1e-6f)
        assertEquals(0.91f, back.sheetHeight, 1e-6f)
        assertEquals(0.203f, back.pitch, 1e-6f)
        assertEquals(TNutPattern.STAGGERED, back.pattern)
        assertEquals(0.125f, back.offsetU, 1e-6f)
        assertEquals(0.05f, back.offsetV, 1e-6f)
    }

    @Test
    fun validityBoundsTheSizePitchAndOffset() {
        assertTrue(map(4f, 4f).valid)
        assertFalse(map(0.2f, 4f).valid)
        assertFalse(WallMap(4f, 4f, 0.91f, 1.82f, 0.02f, TNutPattern.SQUARE, 0.01f, 0.01f).valid)
        assertFalse(WallMap(4f, 4f, 0.91f, 1.82f, 0.2f, TNutPattern.SQUARE, 0.3f, 0.1f).valid)
    }
}
