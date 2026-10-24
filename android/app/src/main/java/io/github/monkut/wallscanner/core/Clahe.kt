package io.github.monkut.wallscanner.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Contrast-limited adaptive histogram equalisation of brightness: the hold model's input step, the same as
 * tools/holds_model/clahe.py (the model was trained on images through it, so the two must match). Only luma
 * (Y of YCrCb) changes; every channel shifts by the change in luma, which keeps the chroma and so the hold
 * colours. The image is cut into [GRID] × [GRID] tiles; each tile's luma histogram is clipped at [CLIP] times
 * the mean bin, the excess spread evenly, and its cumulative sum is the tile's lookup table; a pixel's new
 * luma interpolates the four nearest tiles' tables bilinearly.
 */
object Clahe {
    const val GRID = 8
    const val CLIP = 2.0

    fun equalize(image: Image): Image {
        val w = image.width; val h = image.height
        val px = image.pixels
        val yf = DoubleArray(w * h)
        val y = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            yf[i] = 0.299 * (p shr 16 and 0xff) + 0.587 * (p shr 8 and 0xff) + 0.114 * (p and 0xff)
            y[i] = floor(yf[i] + 0.5).toInt().coerceIn(0, 255)
        }
        val lut = Array(GRID * GRID) { DoubleArray(256) }
        for (ty in 0 until GRID) for (tx in 0 until GRID) {
            val y0 = ty * h / GRID; val y1 = (ty + 1) * h / GRID
            val x0 = tx * w / GRID; val x1 = (tx + 1) * w / GRID
            val n = (y1 - y0) * (x1 - x0)
            val table = lut[ty * GRID + tx]
            if (n == 0) { for (v in 0 until 256) table[v] = v.toDouble(); continue }
            val hist = LongArray(256)
            for (yy in y0 until y1) for (xx in x0 until x1) hist[y[yy * w + xx]]++
            val limit = max(1L, (CLIP * n / 256).toLong())
            var excess = 0L
            for (v in 0 until 256) if (hist[v] > limit) { excess += hist[v] - limit; hist[v] = limit }
            var cum = 0L
            for (v in 0 until 256) {
                cum += hist[v] + excess / 256
                table[v] = floor(cum * 255.0 / n + 0.5).coerceIn(0.0, 255.0)
            }
        }
        val tileW = w.toDouble() / GRID; val tileH = h.toDouble() / GRID
        val out = IntArray(w * h)
        for (yy in 0 until h) {
            val gy = (yy + 0.5) / tileH - 0.5
            val ty0 = floor(gy).toInt().coerceIn(0, GRID - 1); val ty1 = min(ty0 + 1, GRID - 1); val ay = (gy - ty0).coerceIn(0.0, 1.0)
            for (xx in 0 until w) {
                val gx = (xx + 0.5) / tileW - 0.5
                val tx0 = floor(gx).toInt().coerceIn(0, GRID - 1); val tx1 = min(tx0 + 1, GRID - 1); val ax = (gx - tx0).coerceIn(0.0, 1.0)
                val i = yy * w + xx
                val v = y[i]
                val top = lut[ty0 * GRID + tx0][v] * (1 - ax) + lut[ty0 * GRID + tx1][v] * ax
                val bottom = lut[ty1 * GRID + tx0][v] * (1 - ax) + lut[ty1 * GRID + tx1][v] * ax
                val shift = top * (1 - ay) + bottom * ay - yf[i]
                val p = px[i]
                val r = floor((p shr 16 and 0xff) + shift + 0.5).toInt().coerceIn(0, 255)
                val g = floor((p shr 8 and 0xff) + shift + 0.5).toInt().coerceIn(0, 255)
                val b = floor((p and 0xff) + shift + 0.5).toInt().coerceIn(0, 255)
                out[i] = (p and 0xff000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
        return Image(w, h, out)
    }
}
