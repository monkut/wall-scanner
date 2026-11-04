package io.github.monkut.wallscanner.climbs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import io.github.monkut.wallscanner.core.FaceBox
import kotlin.math.min

/** Draws face masks: a solid rounded rectangle in the panel colour with a chalk hairline, the same on playback and in shared clips. */
object MaskPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#212830"); style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EFEBE3"); style = Paint.Style.STROKE }

    /** [boxes] (normalised) drawn over the video shown at ([left], [top]) with [width] × [height] px. */
    fun draw(canvas: Canvas, boxes: List<FaceBox>, left: Float, top: Float, width: Float, height: Float) {
        edge.strokeWidth = (min(width, height) / 400f).coerceAtLeast(1f)
        for (b in boxes) {
            val r = RectF(left + b.box.x * width, top + b.box.y * height, left + b.box.right * width, top + b.box.bottom * height)
            val radius = min(r.width(), r.height()) * 0.3f
            canvas.drawRoundRect(r, radius, radius, fill)
            canvas.drawRoundRect(r, radius, radius, edge)
        }
    }
}
