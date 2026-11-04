package io.github.monkut.wallscanner.climbs

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import io.github.monkut.wallscanner.core.FaceFrame
import io.github.monkut.wallscanner.core.FaceMask

/**
 * Face masks over a playing clip: laid over the VideoView (same parent), it draws the boxes for [timeMs] from
 * [frames] onto the area the VideoView occupies ([target]), which is the video's own aspect ratio.
 */
class FaceMaskView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var frames: List<FaceFrame> = emptyList()
        set(v) { field = v; invalidate() }
    var climber = true
    var bystanders = true
    var target: View? = null
    var timeMs: Long = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val v = target ?: return
        val boxes = FaceMask.boxesAt(frames, timeMs, climber, bystanders)
        if (boxes.isEmpty()) return
        MaskPainter.draw(canvas, boxes, v.left.toFloat(), v.top.toFloat(), v.width.toFloat(), v.height.toFloat())
    }
}
