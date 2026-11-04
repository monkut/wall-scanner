package io.github.monkut.wallscanner.core

import kotlin.math.min

/** Where the caption sits on a climb video. */
enum class Corner(val id: String, val right: Boolean, val bottom: Boolean) {
    UPPER_LEFT("upper-left", right = false, bottom = false),
    UPPER_RIGHT("upper-right", right = true, bottom = false),
    LOWER_LEFT("lower-left", right = false, bottom = true),
    LOWER_RIGHT("lower-right", right = true, bottom = true);

    companion object {
        val DEFAULT = LOWER_LEFT
        fun fromId(id: String?): Corner = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** A typeface the caption can use: the app's own (Atkinson Hyperlegible, [family] null) or a family every Android ships. */
enum class CaptionFont(val id: String, val family: String?) {
    ATKINSON("atkinson", null),
    SANS("sans", "sans-serif"),
    CONDENSED("condensed", "sans-serif-condensed"),
    SERIF("serif", "serif"),
    MONO("mono", "monospace"),
    CASUAL("casual", "casual"),
    CURSIVE("cursive", "cursive");

    companion object {
        val DEFAULT = ATKINSON
        fun fromId(id: String?): CaptionFont = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** What the caption says: [title] is the route (name and grade), [detail] the gym, wall and set. */
class Caption(val title: String, val detail: String) {
    val empty: Boolean get() = title.isBlank() && detail.isBlank()
}

/** Caption geometry in frame pixels, shared by playback and the shared copy so both look the same (issue #13). */
object CaptionLayout {
    /** Title size as a fraction of the frame height; the detail line a size smaller. */
    const val TITLE_FRACTION = 0.035f
    const val DETAIL_FRACTION = 0.025f
    /** Distance from the frame's edges, as a fraction of its shorter side. */
    const val INSET_FRACTION = 0.03f
    /** Backdrop padding and corner radius, as a fraction of the title size. */
    const val PAD_FRACTION = 0.4f
    /** Gap between the two lines, as a fraction of the detail size. */
    const val GAP_FRACTION = 0.3f
    /** A long route name shrinks both lines until the wider one fits this much of the frame's width. */
    const val MAX_WIDTH_FRACTION = 0.9f

    /** Top-left of a [w] × [h] block in [corner] of a [frameW] × [frameH] frame, [inset] from its edges. */
    fun place(corner: Corner, w: Float, h: Float, frameW: Float, frameH: Float, inset: Float): Pair<Float, Float> =
        (if (corner.right) frameW - inset - w else inset) to (if (corner.bottom) frameH - inset - h else inset)

    /**
     * Title and detail text sizes for a [frameW] × [frameH] frame: the fractions of its height, scaled down together
     * when the wider line ([titleWidthPerPx] and [detailWidthPerPx] are each line's width at a text size of 1 px)
     * would pass [MAX_WIDTH_FRACTION] of the width.
     */
    fun sizes(frameW: Float, frameH: Float, titleWidthPerPx: Float, detailWidthPerPx: Float): Pair<Float, Float> {
        val title = frameH * TITLE_FRACTION
        val detail = frameH * DETAIL_FRACTION
        val widest = maxOf(titleWidthPerPx * title, detailWidthPerPx * detail)
        val limit = frameW * MAX_WIDTH_FRACTION
        val scale = if (widest > limit && widest > 0f) limit / widest else 1f
        return title * scale to detail * scale
    }

    fun inset(frameW: Float, frameH: Float): Float = min(frameW, frameH) * INSET_FRACTION
}
