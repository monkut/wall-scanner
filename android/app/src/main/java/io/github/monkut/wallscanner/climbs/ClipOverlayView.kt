package io.github.monkut.wallscanner.climbs

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import io.github.monkut.wallscanner.core.FaceFrame
import io.github.monkut.wallscanner.core.FaceMask

/**
 * What is drawn over a playing clip: the face masks for [timeMs] from [frames] and the [caption], onto the area
 * the VideoView occupies ([target], the video's own aspect ratio). Laid over the VideoView in the same parent.
 */
class ClipOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var frames: List<FaceFrame> = emptyList()
        set(v) { field = v; invalidate() }
    var climber = true
    var bystanders = true
    var caption: CaptionPainter? = null
        set(v) { field = v; invalidate() }
    var target: View? = null
    var timeMs: Long = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val v = target ?: return
        val left = v.left.toFloat(); val top = v.top.toFloat(); val w = v.width.toFloat(); val h = v.height.toFloat()
        val boxes = FaceMask.boxesAt(frames, timeMs, climber, bystanders)
        if (boxes.isNotEmpty()) MaskPainter.draw(canvas, boxes, left, top, w, h)
        caption?.draw(canvas, left, top, w, h)
    }
}
