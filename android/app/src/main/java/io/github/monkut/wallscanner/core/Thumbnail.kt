package io.github.monkut.wallscanner.core

import kotlin.math.max
import kotlin.math.min

/** ARGB pixels, row-major, top row first. */
class Image(val width: Int, val height: Int, val pixels: IntArray)

/** Front view of a wall scan: points projected into the wall frame, front-most point per pixel wins. */
object Thumbnail {
    private const val BACKGROUND = 0xff202020.toInt()
    private const val MIN_RELIEF = -0.1f
    private const val MAX_RELIEF = 0.6f
    private const val SPLAT = 2 // pixels per point, fills gaps between 1 cm voxels

    fun render(cloud: VoxelCloud, frame: WallFrame, width: Int = 320, height: Int = 240): Image {
        val n = cloud.count
        val u = FloatArray(n); val v = FloatArray(n); val d = FloatArray(n)
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = cloud.xyz[i * 3]; val y = cloud.xyz[i * 3 + 1]; val z = cloud.xyz[i * 3 + 2]
            d[i] = frame.d(x, y, z)
            if (d[i] < MIN_RELIEF || d[i] > MAX_RELIEF) continue
            u[i] = frame.u(x, y, z); v[i] = frame.v(x, y, z)
            minU = min(minU, u[i]); maxU = max(maxU, u[i]); minV = min(minV, v[i]); maxV = max(maxV, v[i])
        }
        val pixels = IntArray(width * height) { BACKGROUND }
        if (minU >= maxU || minV >= maxV) return Image(width, height, pixels)
        val scale = min((width - 2 * SPLAT) / (maxU - minU), (height - 2 * SPLAT) / (maxV - minV))
        val offX = (width - (maxU - minU) * scale) / 2
        val offY = (height - (maxV - minV) * scale) / 2
        val depth = FloatArray(width * height) { -Float.MAX_VALUE }
        for (i in 0 until n) {
            if (d[i] < MIN_RELIEF || d[i] > MAX_RELIEF) continue
            val px = (offX + (u[i] - minU) * scale).toInt()
            val py = (height - 1 - (offY + (v[i] - minV) * scale)).toInt()
            val color = 0xff000000.toInt() or
                ((cloud.rgb[i * 3].toInt() and 0xff) shl 16) or
                ((cloud.rgb[i * 3 + 1].toInt() and 0xff) shl 8) or
                (cloud.rgb[i * 3 + 2].toInt() and 0xff)
            for (dy in 0 until SPLAT) for (dx in 0 until SPLAT) {
                val x = px + dx; val y = py + dy
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                val k = y * width + x
                if (d[i] > depth[k]) { depth[k] = d[i]; pixels[k] = color }
            }
        }
        return Image(width, height, pixels)
    }
}
