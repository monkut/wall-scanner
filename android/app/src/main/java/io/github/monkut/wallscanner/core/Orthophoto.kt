package io.github.monkut.wallscanner.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** A saved keyframe: world-from-camera pose and the intrinsics of the image as stored. */
class Keyframe(val file: String, val worldFromCamera: Rigid, val fx: Float, val fy: Float, val cx: Float, val cy: Float, val width: Int, val height: Int)

/**
 * Front view of the wall assembled from keyframes: [px] metres per pixel, top row at [maxV]. [relief] is the
 * depth-measured height above the plane at each pixel (0 where the scan has no points), one float per pixel.
 */
class Orthophoto(val image: Image, val minU: Float, val minV: Float, val maxU: Float, val maxV: Float, val px: Float, val relief: FloatArray) {
    fun x(u: Float): Float = (u - minU) / px
    fun y(v: Float): Float = (maxV - v) / px
    fun u(x: Float): Float = minU + x * px
    fun v(y: Float): Float = maxV - y * px
}

/**
 * Projects every wall pixel, placed at its depth-measured relief, through each keyframe and keeps the most
 * frontal, closest view: a sharp orthophoto of the wall from the photos the scan already took. Hold shapes
 * are then read from it ([HoldShapes]) and the route image draws it.
 */
object OrthophotoBuilder {
    const val MM_PER_PX = 5f
    private const val MARGIN = 0.1f
    private const val EDGE_PX_FRACTION = 0.0125f // ignore samples this close to a keyframe's border (vignetting)
    private const val MIN_DEPTH = 0.3f
    private const val RELIEF_CELL = 0.02f
    private const val MAX_RELIEF = 0.8f // volumes included, as for hold detection

    /**
     * [extent] is the scanned wall area [minU, minV, maxU, maxV]; [decode] returns a keyframe's pixels (ARGB,
     * any scale: intrinsics are scaled to the decoded size) or null to skip it.
     */
    fun build(cloud: VoxelCloud, frame: WallFrame, extent: FloatArray, keyframes: List<Keyframe>, decode: (Keyframe) -> Image?, mmPerPx: Float = MM_PER_PX): Orthophoto? {
        if (keyframes.isEmpty() || extent[2] <= extent[0] || extent[3] <= extent[1]) return null
        val px = mmPerPx / 1000f
        val minU = extent[0] - MARGIN; val minV = extent[1] - MARGIN; val maxU = extent[2] + MARGIN; val maxV = extent[3] + MARGIN
        val w = ((maxU - minU) / px).toInt() + 1
        val h = ((maxV - minV) / px).toInt() + 1
        if (w.toLong() * h > 4_000_000L) return null
        val relief = reliefMap(cloud, frame, minU, minV, maxU, maxV)

        // World position of every pixel (at its relief), row 0 = top (maxV).
        val n = w * h
        val wx = FloatArray(n); val wy = FloatArray(n); val wz = FloatArray(n)
        val reliefPx = FloatArray(n)
        val p = frame.plane
        for (y in 0 until h) {
            val v = maxV - (y + 0.5f) * px
            for (x in 0 until w) {
                val u = minU + (x + 0.5f) * px
                val d = relief.at(u, v)
                val i = y * w + x
                reliefPx[i] = d
                wx[i] = p.ox + u * frame.rx + v * frame.ux + d * p.nx
                wy[i] = p.oy + u * frame.ry + v * frame.uy + d * p.ny
                wz[i] = p.oz + u * frame.rz + v * frame.uz + d * p.nz
            }
        }
        val best = FloatArray(n)
        val out = IntArray(n)
        var used = 0
        for (k in keyframes) {
            val img = decode(k) ?: continue
            val sx = img.width.toFloat() / k.width; val sy = img.height.toFloat() / k.height
            val fx = k.fx * sx; val fy = k.fy * sy; val cx = k.cx * sx; val cy = k.cy * sy
            val edgeX = img.width * EDGE_PX_FRACTION; val edgeY = img.height * EDGE_PX_FRACTION
            val r = k.worldFromCamera.r; val t = k.worldFromCamera.t
            var hits = 0
            for (i in 0 until n) {
                // Camera-space point: R^T (P - t); ARCore camera looks down -Z with +Y up.
                val dx = wx[i] - t[0]; val dy = wy[i] - t[1]; val dz = wz[i] - t[2]
                val xc = r[0] * dx + r[3] * dy + r[6] * dz
                val yc = r[1] * dx + r[4] * dy + r[7] * dz
                val zc = r[2] * dx + r[5] * dy + r[8] * dz
                val depth = -zc
                if (depth < MIN_DEPTH) continue
                val ix = (fx * xc / depth + cx).toFloat()
                val iy = (cy - fy * yc / depth).toFloat()
                if (ix < edgeX || iy < edgeY || ix >= img.width - 1 - edgeX || iy >= img.height - 1 - edgeY) continue
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                val cos = abs((dx * p.nx + dy * p.ny + dz * p.nz) / dist)
                val score = (cos * cos / max(dist, 0.5)).toFloat()
                if (score <= best[i]) continue
                best[i] = score
                out[i] = bilinear(img, ix, iy)
                hits++
            }
            if (hits > 0) used++
        }
        if (used == 0) return null
        return Orthophoto(Image(w, h, out), minU, minV, maxU, maxV, px, reliefPx)
    }

    private fun bilinear(img: Image, x: Float, y: Float): Int {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val p = img.pixels; val w = img.width
        val c00 = p[y0 * w + x0]; val c10 = p[y0 * w + x0 + 1]; val c01 = p[(y0 + 1) * w + x0]; val c11 = p[(y0 + 1) * w + x0 + 1]
        fun ch(shift: Int): Int {
            val a = (c00 shr shift and 0xff) * (1 - fx) + (c10 shr shift and 0xff) * fx
            val b = (c01 shr shift and 0xff) * (1 - fx) + (c11 shr shift and 0xff) * fx
            return (a * (1 - fy) + b * fy + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Smoothed per-cell median relief (clamped to 0..[MAX_RELIEF]) with bilinear lookup. */
    private class ReliefMap(val w: Int, val h: Int, val minU: Float, val minV: Float, val cell: Float, val values: FloatArray) {
        fun at(u: Float, v: Float): Float {
            val fx = ((u - minU) / cell - 0.5f).coerceIn(0f, (w - 1).toFloat())
            val fy = ((v - minV) / cell - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val x0 = fx.toInt().coerceAtMost(w - 2).coerceAtLeast(0); val y0 = fy.toInt().coerceAtMost(h - 2).coerceAtLeast(0)
            val tx = fx - x0; val ty = fy - y0
            val a = values[y0 * w + x0] * (1 - tx) + values[y0 * w + x0 + 1] * tx
            val b = values[(y0 + 1) * w + x0] * (1 - tx) + values[(y0 + 1) * w + x0 + 1] * tx
            return a * (1 - ty) + b * ty
        }
    }

    private fun reliefMap(cloud: VoxelCloud, frame: WallFrame, minU: Float, minV: Float, maxU: Float, maxV: Float): ReliefMap {
        val cw = max(2, ((maxU - minU) / RELIEF_CELL).toInt() + 1); val ch = max(2, ((maxV - minV) / RELIEF_CELL).toInt() + 1)
        val xyz = cloud.xyz
        val cellOf = IntArray(cloud.count) { -1 }
        val count = IntArray(cw * ch)
        for (i in 0 until cloud.count) {
            val x = xyz[i * 3]; val y = xyz[i * 3 + 1]; val z = xyz[i * 3 + 2]
            val d = frame.d(x, y, z)
            if (d < -0.3f || d > 0.6f) continue
            val cx = ((frame.u(x, y, z) - minU) / RELIEF_CELL).toInt(); val cy = ((frame.v(x, y, z) - minV) / RELIEF_CELL).toInt()
            if (cx < 0 || cy < 0 || cx >= cw || cy >= ch) continue
            cellOf[i] = cy * cw + cx; count[cellOf[i]]++
        }
        val start = IntArray(cw * ch + 1)
        for (c in 0 until cw * ch) start[c + 1] = start[c] + count[c]
        val cursor = start.copyOf(cw * ch)
        val order = FloatArray(start[cw * ch])
        for (i in 0 until cloud.count) {
            val c = cellOf[i]
            if (c >= 0) order[cursor[c]++] = frame.d(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])
        }
        val median = FloatArray(cw * ch)
        for (c in 0 until cw * ch) {
            val m = start[c + 1] - start[c]
            if (m == 0) continue
            java.util.Arrays.sort(order, start[c], start[c + 1])
            median[c] = order[start[c] + m / 2].coerceIn(0f, MAX_RELIEF)
        }
        val smooth = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            var s = 0f; var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue
                s += median[ny * cw + nx]; k++
            }
            smooth[y * cw + x] = s / k
        }
        return ReliefMap(cw, ch, minU, minV, RELIEF_CELL, smooth)
    }
}
