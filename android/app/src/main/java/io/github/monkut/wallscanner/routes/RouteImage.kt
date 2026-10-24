package io.github.monkut.wallscanner.routes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import io.github.monkut.wallscanner.R
import io.github.monkut.wallscanner.core.HoldRole
import io.github.monkut.wallscanner.io.CaptureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Front view of one route for the logbook, orthographic in the wall plane (distances are true along the
 * wall, up is the wall's up). With an orthophoto (wall.png) the wall is shown as photographed, dimmed
 * everywhere but inside the route's holds, which are outlined in chalk. Without one the wall is a flat
 * surface colour with a faint half-metre grid, the route's holds filled in their own colours and every other
 * hold a grey silhouette. Start and top holds are ringed in tape yellow; the wall angle and a 1 m scale sit
 * in the corners.
 * Start and top come from hold roles when any are set (tape detection or a tap on this image); otherwise the
 * lowest hold(s) and the highest are guessed and ringed with a dashed line.
 */
object RouteImage {
    private const val TARGET_WIDTH = 1600
    private const val MAX_HEIGHT = 2600
    private const val MARGIN_M = 0.15f
    private const val MIN_HOLD_M = 0.05f // smallest drawn hold when only a size is known
    private const val GRID_M = 0.5f
    private const val VERTICAL_TOLERANCE_DEG = 3
    private const val TWO_HAND_START_DV = 0.35f
    private const val TWO_HAND_START_DU = 1.0f
    private val DEFAULT_WALL = Color.rgb(201, 186, 160)

    /** A route hold's tap target in bitmap pixels. */
    class Hit(val holdId: Int, val rect: RectF)

    /** [marked] is true when both start and top came from hold roles; [anyMarked] when at least one did. */
    class Rendered(val bitmap: Bitmap, val hits: List<Hit>, val marked: Boolean, val anyMarked: Boolean)

    private class HoldShape(
        val id: Int, val u: Float, val v: Float, val w: Float, val tall: Float,
        val color: Int, val role: HoldRole, val inRoute: Boolean, val outline: FloatArray,
    )

    fun render(context: Context, dir: File, routeId: Int): Rendered? {
        val scene = JSONObject(CaptureStore.readScene(dir))
        val routes = scene.getJSONArray("routes")
        var route: JSONObject? = null
        for (i in 0 until routes.length()) if (routes.getJSONObject(i).getInt("id") == routeId) route = routes.getJSONObject(i)
        val chosen = route ?: return null
        val holdIds = chosen.getJSONArray("holdIds").let { ids -> (0 until ids.length()).map { ids.getInt(it) }.toSet() }
        val holdsJson = scene.getJSONArray("holds")
        val holds = (0 until holdsJson.length()).map { i ->
            val h = holdsJson.getJSONObject(i)
            val uv = h.getJSONArray("uv"); val size = h.getJSONArray("size"); val c = h.getJSONArray("color")
            val outline = h.optJSONArray("outline")?.let { o -> FloatArray(o.length() * 2) { k -> o.getJSONArray(k / 2).getDouble(k % 2).toFloat() } } ?: FloatArray(0)
            HoldShape(
                h.getInt("id"), uv.getDouble(0).toFloat(), uv.getDouble(1).toFloat(),
                max(MIN_HOLD_M, size.getDouble(0).toFloat()), max(MIN_HOLD_M, size.getDouble(1).toFloat()),
                Color.rgb(c.getInt(0), c.getInt(1), c.getInt(2)),
                HoldRole.fromId(h.optString("role")), h.getInt("id") in holdIds, outline,
            )
        }
        val wall = scene.getJSONObject("wall")
        val wallColor = wall.optJSONArray("color")?.let { Color.rgb(it.getInt(0), it.getInt(1), it.getInt(2)) } ?: DEFAULT_WALL
        val normalY = wall.getJSONArray("normal").getDouble(1).toFloat()
        val photo = wall.optJSONObject("photo")?.let { p ->
            val b = p.getJSONArray("bounds")
            val bitmap = BitmapFactory.decodeFile(File(dir, p.getString("file")).path) ?: return@let null
            Photo(bitmap, RectF(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()))
        }
        val area = photo?.bounds?.let { RectF(it) } ?: bounds(wall.optJSONArray("extent"), holds)
        return draw(context, holds, area, wallColor, normalY, photo)
    }

    /** The wall orthophoto (wall.png) and the wall-frame rectangle it covers (left = minU, top = minV, right = maxU, bottom = maxV). */
    private class Photo(val bitmap: Bitmap, val bounds: RectF)

    /** The scanned wall extent, grown to cover every route hold, plus a margin; the holds' span when unknown. */
    private fun bounds(extent: JSONArray?, holds: List<HoldShape>): RectF {
        val b = if (extent != null && extent.length() == 4 && extent.getDouble(2) > extent.getDouble(0)) {
            RectF(extent.getDouble(0).toFloat(), extent.getDouble(1).toFloat(), extent.getDouble(2).toFloat(), extent.getDouble(3).toFloat())
        } else {
            RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { r -> for (h in holds) r.union(h.u, h.v) }
        }
        for (h in holds) if (h.inRoute) b.union(RectF(h.u - h.w / 2, h.v - h.tall / 2, h.u + h.w / 2, h.v + h.tall / 2))
        b.inset(-MARGIN_M, -MARGIN_M)
        if (b.width() < 1f) b.inset(-(1f - b.width()) / 2, 0f)
        if (b.height() < 1f) b.inset(0f, -(1f - b.height()) / 2)
        return b
    }

    private fun draw(context: Context, holds: List<HoldShape>, area: RectF, wallColor: Int, normalY: Float, photo: Photo?): Rendered {
        var scale = TARGET_WIDTH / area.width()
        if (area.height() * scale > MAX_HEIGHT) scale = MAX_HEIGHT / area.height()
        val width = (area.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (area.height() * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        fun px(u: Float) = (u - area.left) * scale
        fun py(v: Float) = (area.bottom - v) * scale

        val chalk = ContextCompat.getColor(context, R.color.chalk)
        val tape = ContextCompat.getColor(context, R.color.tape)
        val shadow = 0x99000000.toInt()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

        val path = Path()
        fun shape(h: HoldShape, into: Path = path): Path {
            into.reset()
            if (h.outline.size >= 6) {
                into.moveTo(px(h.outline[0]), py(h.outline[1]))
                for (i in 1 until h.outline.size / 2) into.lineTo(px(h.outline[i * 2]), py(h.outline[i * 2 + 1]))
                into.close()
            } else {
                into.addOval(RectF(px(h.u - h.w / 2), py(h.v + h.tall / 2), px(h.u + h.w / 2), py(h.v - h.tall / 2)), Path.Direction.CW)
            }
            return into
        }
        val routeHolds = holds.filter { it.inRoute }

        if (photo != null) {
            // The wall as photographed; everything but the route's holds is dimmed, so they read at a glance.
            val b = photo.bounds
            canvas.drawBitmap(photo.bitmap, null, RectF(px(b.left), py(b.bottom), px(b.right), py(b.top)), Paint(Paint.FILTER_BITMAP_FLAG))
            val keep = Path()
            for (h in routeHolds) keep.addPath(shape(h, Path()))
            canvas.save()
            canvas.clipOutPath(keep)
            canvas.drawColor(ContextCompat.getColor(context, R.color.overlay))
            canvas.restore()
            for (h in routeHolds) {
                val p = shape(h)
                stroke.color = shadow; stroke.strokeWidth = 0.02f * scale; canvas.drawPath(p, stroke)
                stroke.color = chalk; stroke.strokeWidth = 0.01f * scale; canvas.drawPath(p, stroke)
            }
        } else {
            // No orthophoto: flat surface colour and a faint grid every half metre (stronger each metre).
            canvas.drawColor(wallColor)
            stroke.color = 0x14000000
            var g = Math.ceil((area.left / GRID_M).toDouble()).toFloat() * GRID_M
            while (g < area.right) {
                stroke.strokeWidth = if (abs(g / 1f - Math.round(g)) < 0.01f) 0.008f * scale else 0.004f * scale
                canvas.drawLine(px(g), 0f, px(g), height.toFloat(), stroke); g += GRID_M
            }
            g = Math.ceil((area.top / GRID_M).toDouble()).toFloat() * GRID_M
            while (g < area.bottom) {
                stroke.strokeWidth = if (abs(g / 1f - Math.round(g)) < 0.01f) 0.008f * scale else 0.004f * scale
                canvas.drawLine(0f, py(g), width.toFloat(), py(g), stroke); g += GRID_M
            }
            // Other holds as grey silhouettes: the wall's layout without competing colours.
            for (h in holds) {
                if (h.inRoute) continue
                val p = shape(h)
                fill.color = 0x2E000000; canvas.drawPath(p, fill)
                stroke.color = 0x33FFFFFF; stroke.strokeWidth = 0.006f * scale; canvas.drawPath(p, stroke)
            }
            for (h in routeHolds) {
                val p = shape(h)
                stroke.color = shadow; stroke.strokeWidth = 0.024f * scale; canvas.drawPath(p, stroke)
                stroke.color = chalk; stroke.strokeWidth = 0.012f * scale; canvas.drawPath(p, stroke)
                fill.color = h.color; canvas.drawPath(p, fill)
            }
        }

        val (starts, tops) = startAndTop(routeHolds)
        val marked = starts.all { it.marked } && tops.all { it.marked }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 0.14f * scale; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val dash = DashPathEffect(floatArrayOf(0.06f * scale, 0.04f * scale), 0f)
        val ring = RectF()
        fun mark(m: Mark, text: String, above: Boolean) {
            val h = m.hold
            ring.set(px(h.u - h.w / 2 - 0.05f), py(h.v + h.tall / 2 + 0.05f), px(h.u + h.w / 2 + 0.05f), py(h.v - h.tall / 2 - 0.05f))
            stroke.pathEffect = if (m.marked) null else dash
            stroke.color = shadow; stroke.strokeWidth = 0.034f * scale; canvas.drawOval(ring, stroke)
            stroke.color = tape; stroke.strokeWidth = 0.02f * scale; canvas.drawOval(ring, stroke)
            stroke.pathEffect = null
            // Label above or below the ring, flipped when that would leave the image (holds at the wall's top or bottom edge).
            var y = if (above) ring.top - 0.06f * scale else ring.bottom + 0.16f * scale
            if (y - label.textSize < 0f) y = ring.bottom + 0.16f * scale
            if (y > height - 0.04f * scale) y = ring.top - 0.06f * scale
            val x = ring.centerX().coerceIn(label.measureText(text) / 2 + 0.02f * scale, width - label.measureText(text) / 2 - 0.02f * scale)
            drawLabel(canvas, label, text, x, y, tape, shadow)
        }
        for (h in starts) mark(h, context.getString(R.string.label_start), above = false)
        for (h in tops) mark(h, context.getString(R.string.label_top), above = true)

        // Wall angle (top left) and 1 m scale bar (bottom left).
        val tilt = Math.toDegrees(asin((-normalY).coerceIn(-1f, 1f).toDouble())).roundToInt()
        val angleText = when {
            abs(tilt) <= VERTICAL_TOLERANCE_DEG -> context.getString(R.string.wall_vertical)
            tilt > 0 -> context.getString(R.string.wall_overhang, tilt)
            else -> context.getString(R.string.wall_slab, -tilt)
        }
        label.textAlign = Paint.Align.LEFT; label.textSize = 0.16f * scale
        val pad = 0.08f * scale
        drawLabel(canvas, label, angleText, pad, pad + label.textSize, chalk, shadow)
        drawAngleGlyph(canvas, pad + label.measureText(angleText) + 0.12f * scale, pad + label.textSize * 0.5f, 0.07f * scale, tilt, chalk, shadow)
        val barY = height - pad
        val meter = min(1f, area.width() / 2) * scale
        stroke.color = shadow; stroke.strokeWidth = 0.05f * scale; canvas.drawLine(pad, barY, pad + meter, barY, stroke)
        stroke.color = chalk; stroke.strokeWidth = 0.025f * scale; canvas.drawLine(pad, barY, pad + meter, barY, stroke)
        label.textSize = 0.12f * scale
        drawLabel(canvas, label, if (meter >= scale) "1 m" else "${(meter / scale * 100).roundToInt()} cm", pad, barY - 0.06f * scale, chalk, shadow)

        val hits = routeHolds.map { h ->
            Hit(h.id, RectF(px(h.u - h.w / 2 - 0.04f), py(h.v + h.tall / 2 + 0.04f), px(h.u + h.w / 2 + 0.04f), py(h.v - h.tall / 2 - 0.04f)))
        }
        return Rendered(bitmap, hits, marked, (starts + tops).any { it.marked })
    }

    /** Text with a dark halo so it reads on any wall colour. */
    private fun drawLabel(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, color: Int, halo: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = paint.textSize * 0.18f; paint.color = halo
        canvas.drawText(text, x, y, paint)
        paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawText(text, x, y, paint)
    }

    /** Side view of the wall: a floor line and the wall leaning by [tilt] degrees (positive = overhang). */
    private fun drawAngleGlyph(canvas: Canvas, x: Float, cy: Float, r: Float, tilt: Int, color: Int, halo: Int) {
        val rad = Math.toRadians(tilt.toDouble())
        val topX = (x + r * Math.sin(rad)).toFloat(); val topY = (cy - r * Math.cos(rad)).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        for ((c, w) in listOf(halo to r * 0.5f, color to r * 0.25f)) {
            paint.color = c; paint.strokeWidth = w
            canvas.drawLine(x - r * 0.8f, cy + r, x + r * 0.8f, cy + r, paint)
            canvas.drawLine(x, cy + r, topX, topY, paint)
        }
    }

    /** A start or top hold and whether it comes from a role (tape, viewer or a tap) rather than from height. */
    private class Mark(val hold: HoldShape, val marked: Boolean)

    /** Start and top holds: roles where set, the lowest one or two holds and the highest otherwise. */
    private fun startAndTop(route: List<HoldShape>): Pair<List<Mark>, List<Mark>> {
        if (route.isEmpty()) return Pair(emptyList(), emptyList())
        val markedStart = route.filter { it.role == HoldRole.START }.map { Mark(it, true) }
        val markedTop = route.filter { it.role == HoldRole.FINISH }.map { Mark(it, true) }
        val byHeight = route.sortedBy { it.v }
        val starts = markedStart.ifEmpty {
            val guess = arrayListOf(Mark(byHeight[0], false))
            if (byHeight.size > 2) {
                val second = byHeight[1]
                if (second.v - byHeight[0].v <= TWO_HAND_START_DV && abs(second.u - byHeight[0].u) <= TWO_HAND_START_DU) guess.add(Mark(second, false))
            }
            guess
        }
        val tops = markedTop.ifEmpty {
            val top = byHeight.last()
            if (starts.any { it.hold === top }) emptyList() else listOf(Mark(top, false))
        }
        return Pair(starts, tops)
    }
}
