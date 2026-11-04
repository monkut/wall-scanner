package io.github.monkut.wallscanner.climbs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import io.github.monkut.wallscanner.R
import io.github.monkut.wallscanner.core.Caption
import io.github.monkut.wallscanner.core.CaptionFont
import io.github.monkut.wallscanner.core.CaptionLayout
import io.github.monkut.wallscanner.core.Corner

/**
 * Draws the caption (route on one line, gym · wall · set under it) in a corner of the video: chalk text on a
 * translucent panel-colour backdrop, left-aligned in a left corner and right-aligned in a right one. The same
 * painter serves playback ([ClipOverlayView]) and the shared copy ([OverlayExport]).
 */
class CaptionPainter(context: Context, font: CaptionFont, private val corner: Corner, val caption: Caption) {
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CHALK; typeface = typeface(context, font, bold = true) }
    private val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CHALK; typeface = typeface(context, font, bold = false) }
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKDROP; style = Paint.Style.FILL }

    /** The caption over the video shown at ([left], [top]) with [width] × [height] px. */
    fun draw(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        if (caption.empty || width <= 0f || height <= 0f) return
        val (titleSize, detailSize) = CaptionLayout.sizes(width, height, widthPerPx(title, caption.title), widthPerPx(detail, caption.detail))
        title.textSize = titleSize
        detail.textSize = detailSize
        val lines = listOfNotNull(caption.title.takeIf { it.isNotBlank() }?.let { it to title }, caption.detail.takeIf { it.isNotBlank() }?.let { it to detail })
        val pad = titleSize * CaptionLayout.PAD_FRACTION
        val gap = detailSize * CaptionLayout.GAP_FRACTION
        val textWidth = lines.maxOf { (text, paint) -> paint.measureText(text) }
        val textHeight = lines.sumOf { (_, paint) -> (paint.fontMetrics.descent - paint.fontMetrics.ascent).toDouble() }.toFloat() + gap * (lines.size - 1)
        val w = textWidth + 2 * pad
        val h = textHeight + 2 * pad
        val (x, y) = CaptionLayout.place(corner, w, h, width, height, CaptionLayout.inset(width, height))
        canvas.drawRoundRect(RectF(left + x, top + y, left + x + w, top + y + h), pad, pad, backdrop)
        var baseline = top + y + pad
        for ((text, paint) in lines) {
            paint.textAlign = if (corner.right) Paint.Align.RIGHT else Paint.Align.LEFT
            baseline -= paint.fontMetrics.ascent
            canvas.drawText(text, if (corner.right) left + x + w - pad else left + x + pad, baseline, paint)
            baseline += paint.fontMetrics.descent + gap
        }
    }

    /** A line's width at a text size of 1 px, for [CaptionLayout.sizes]. */
    private fun widthPerPx(paint: Paint, text: String): Float {
        if (text.isBlank()) return 0f
        paint.textSize = MEASURE_SIZE
        return paint.measureText(text) / MEASURE_SIZE
    }

    companion object {
        private const val CHALK = 0xFFEFEBE3.toInt()
        private const val BACKDROP = 0xB3212830.toInt() // panel at 70%
        private const val MEASURE_SIZE = 100f

        /** The typeface for [font]: the bundled Atkinson files, or the system family in the weight asked for. */
        fun typeface(context: Context, font: CaptionFont, bold: Boolean): Typeface =
            if (font.family == null) ResourcesCompat.getFont(context, if (bold) R.font.atkinson_bold else R.font.atkinson_regular) ?: Typeface.DEFAULT_BOLD
            else Typeface.create(font.family, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
}
