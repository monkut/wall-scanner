package io.github.monkut.wallscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.monkut.wallscanner.core.WallMap
import kotlin.math.max
import kotlin.math.min

/** Draws a [WallMap] to scale: the wall's outline, its plywood sheets and every T-nut hole. The live drawing in the wall map form. */
class WallMapView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var map: WallMap? = null
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; color = ContextCompat.getColor(context, R.color.chalk) }
    private val seam = Paint().apply { style = Paint.Style.STROKE; strokeWidth = density; color = ContextCompat.getColor(context, R.color.dust) }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ContextCompat.getColor(context, R.color.chalk) }

    override fun onDraw(canvas: Canvas) {
        val m = map ?: return
        val pad = 12 * density
        val scale = min((width - 2 * pad) / m.width, (height - 2 * pad) / m.height)
        if (scale <= 0f) return
        val left = (width - m.width * scale) / 2
        val bottom = (height + m.height * scale) / 2
        fun x(u: Float) = left + u * scale
        fun y(v: Float) = bottom - v * scale
        for (s in m.sheets()) canvas.drawRect(x(s.u0), y(s.v1), x(s.u1), y(s.v0), seam)
        val radius = max(1.5f * density, HOLE_DIAMETER * scale / 2)
        for (h in m.holes()) canvas.drawCircle(x(h[0]), y(h[1]), radius, hole)
        canvas.drawRect(x(0f), y(m.height), x(m.width), y(0f), outline)
    }

    private companion object {
        const val HOLE_DIAMETER = 0.011f // metres; a T-nut hole
    }
}
