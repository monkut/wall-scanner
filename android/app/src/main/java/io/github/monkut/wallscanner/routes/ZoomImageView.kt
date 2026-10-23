package io.github.monkut.wallscanner.routes

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * ImageView with pinch-to-zoom, one-finger pan and double-tap zoom. The image starts fitted and centred;
 * zoom runs from fit (1×) to [MAX_ZOOM], and panning never leaves the image's edge inside the view.
 */
class ZoomImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {
    private val matrix = Matrix()
    private val bounds = RectF()
    private var zoom = 1f
    private var fitScale = 1f

    /** Called with a confirmed single tap in image (bitmap) pixel coordinates. */
    var onTap: ((Float, Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            matrix.postTranslate(-distanceX, -distanceY)
            clamp()
            imageMatrix = matrix
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) fit() else zoomBy(DOUBLE_TAP_ZOOM, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val handler = onTap ?: return false
            val inverse = Matrix()
            if (!matrix.invert(inverse)) return false
            val point = floatArrayOf(e.x, e.y)
            inverse.mapPoints(point)
            handler(point[0], point[1])
            return true
        }

        override fun onDown(e: MotionEvent): Boolean = true
    })

    init { scaleType = ScaleType.MATRIX }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Fits the whole image in the view, centred, at zoom 1×. */
    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        fitScale = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        zoom = 1f
        matrix.setScale(fitScale, fitScale)
        matrix.postTranslate((width - d.intrinsicWidth * fitScale) / 2, (height - d.intrinsicHeight * fitScale) / 2)
        imageMatrix = matrix
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val target = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val applied = target / zoom
        if (applied == 1f) return
        zoom = target
        matrix.postScale(applied, applied, focusX, focusY)
        clamp()
        imageMatrix = matrix
    }

    /** Keeps the image covering the view along each axis it is larger than, centred along the others. */
    private fun clamp() {
        val d = drawable ?: return
        bounds.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(bounds)
        val dx = when {
            bounds.width() <= width -> (width - bounds.width()) / 2 - bounds.left
            bounds.left > 0 -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> (height - bounds.height()) / 2 - bounds.top
            bounds.top > 0 -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        matrix.postTranslate(dx, dy)
    }

    private companion object {
        const val MAX_ZOOM = 6f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
