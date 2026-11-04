package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Caption placement and sizing in a video frame; setting ids fall back to the defaults. */
class CaptionTest {
    @Test
    fun blockSitsInsetInTheChosenCorner() {
        val (x1, y1) = CaptionLayout.place(Corner.LOWER_LEFT, 300f, 80f, 1080f, 1920f, 32f)
        assertEquals(32f, x1, 1e-4f); assertEquals(1920f - 32f - 80f, y1, 1e-4f)
        val (x2, y2) = CaptionLayout.place(Corner.UPPER_RIGHT, 300f, 80f, 1080f, 1920f, 32f)
        assertEquals(1080f - 32f - 300f, x2, 1e-4f); assertEquals(32f, y2, 1e-4f)
        assertEquals(1080f * CaptionLayout.INSET_FRACTION, CaptionLayout.inset(1080f, 1920f), 1e-4f)
    }

    @Test
    fun sizesFollowTheFrameHeightAndShrinkForALongLine() {
        val (title, detail) = CaptionLayout.sizes(1080f, 1920f, titleWidthPerPx = 4f, detailWidthPerPx = 6f)
        assertEquals(1920f * CaptionLayout.TITLE_FRACTION, title, 1e-3f)
        assertEquals(1920f * CaptionLayout.DETAIL_FRACTION, detail, 1e-3f)
        // A detail line 40 px wide per px of size would be 1920 px: wider than 90% of 1080, so both lines shrink together.
        val (t2, d2) = CaptionLayout.sizes(1080f, 1920f, titleWidthPerPx = 4f, detailWidthPerPx = 40f)
        assertEquals(1080f * CaptionLayout.MAX_WIDTH_FRACTION, d2 * 40f, 1e-2f)
        assertEquals(title / detail, t2 / d2, 1e-4f)
    }

    @Test
    fun idsRoundTripAndUnknownOnesFallBack() {
        assertEquals(Corner.UPPER_RIGHT, Corner.fromId("upper-right"))
        assertEquals(Corner.LOWER_LEFT, Corner.fromId(null))
        assertEquals(Corner.LOWER_LEFT, Corner.fromId("middle"))
        assertEquals(CaptionFont.SERIF, CaptionFont.fromId("serif"))
        assertEquals(CaptionFont.ATKINSON, CaptionFont.fromId("papyrus"))
        assertTrue(Caption("", " ").empty)
        assertFalse(Caption("Red V3", "").empty)
    }
}
