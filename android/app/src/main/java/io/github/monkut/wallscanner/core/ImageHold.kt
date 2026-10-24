package io.github.monkut.wallscanner.core

import kotlin.math.max

/** A hold built from pixels of the orthophoto: outline traced from them, colour their mean, height the relief under them. */
object ImageHold {
    private const val MAX_POINTS = 96

    fun fromPixels(id: Int, pixels: IntArray, voxels: Int, photo: Orthophoto, frame: WallFrame): Hold {
        val w = photo.image.width; val h = photo.image.height
        var su = 0.0; var sv = 0.0; var sr = 0L; var sg = 0L; var sb = 0L
        var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
        for (i in pixels) {
            val x = i % w; val y = i / w
            su += photo.u(x + 0.5f); sv += photo.v(y + 0.5f)
            val px = photo.image.pixels[i]
            sr += px shr 16 and 0xff; sg += px shr 8 and 0xff; sb += px and 0xff
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        val n = pixels.size
        val cu = (su / n).toFloat(); val cv = (sv / n).toFloat()
        val rel = FloatArray(n) { photo.relief[pixels[it]] }
        rel.sort()
        val height = max(0f, rel[(n * 9) / 10])
        val pl = frame.plane
        return Hold(
            id = id,
            cx = pl.ox + cu * frame.rx + cv * frame.ux + height * pl.nx,
            cy = pl.oy + cu * frame.ry + cv * frame.uy + height * pl.ny,
            cz = pl.oz + cu * frame.rz + cv * frame.uz + height * pl.nz,
            u = cu, v = cv, width = (x1 - x0 + 1) * photo.px, tall = (y1 - y0 + 1) * photo.px, height = height,
            r = (sr / n).toInt(), g = (sg / n).toInt(), b = (sb / n).toInt(),
            voxels = voxels, outline = outlineOf(pixels, photo),
        )
    }

    /** Closed outline (u, v) of a pixel set, traced along pixel edges and smoothed. */
    fun outlineOf(pixels: IntArray, photo: Orthophoto): FloatArray {
        val w = photo.image.width; val h = photo.image.height
        val cells = IntArray(pixels.size) { it }; val cxs = IntArray(pixels.size) { pixels[it] % w }; val cys = IntArray(pixels.size) { (h - 1) - pixels[it] / w }
        val outline = HoldOutline.trace(cells, cxs, cys, photo.px, MAX_POINTS, 3)
        for (i in 0 until outline.size / 2) { outline[i * 2] += photo.minU; outline[i * 2 + 1] += photo.maxV - h * photo.px }
        return outline
    }

    /** The hold's outline, or its size ellipse for holds without one (older scenes). */
    fun polygon(hold: Hold): FloatArray = hold.outline.takeIf { it.size >= 6 } ?: Raster.ellipse(hold.u, hold.v, hold.width, hold.tall)

    /** Pixels of the orthophoto inside each hold's outline, as the index of the smallest hold covering them (-1 = none). */
    fun owners(holds: List<Hold>, photo: Orthophoto): IntArray {
        val areas = holds.map { it.width * it.tall }
        val owner = IntArray(photo.image.width * photo.image.height) { -1 }
        for ((hi, hold) in holds.withIndex()) {
            for (i in Raster.fillPolygon(polygon(hold), photo)) if (owner[i] < 0 || areas[hi] < areas[owner[i]]) owner[i] = hi
        }
        return owner
    }
}
