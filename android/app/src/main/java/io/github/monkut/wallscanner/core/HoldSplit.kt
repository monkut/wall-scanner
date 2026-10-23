package io.github.monkut.wallscanner.core

import kotlin.math.max

/**
 * Splits a hold whose outline covers colour patches of two or more hues. Where the wall turns into another
 * face or a volume, everything stands far off the plane and the relief merges neighbouring holds into one
 * blob; the photo still shows them apart. Patches inside the outline are grouped by mean hue (within
 * [HUE_TOL]); a group counts when it holds at least [MIN_SHARE] of the hold's patch pixels, [MIN_SIDE]² of
 * them, and is [MIN_SIDE] across, so a highlight of another hue does not split a hold. The largest group
 * keeps the hold's id, role, tape and voxels together with every pixel of the outline no other group claims
 * and that is not the wall's colour (a white or grey hold in the blob stays with it); the others become
 * image-only holds (`voxels: 0`), each
 * with the outline of its own pixels, its height from the relief under them. A pixel that lies inside a
 * smaller hold's outline belongs to that hold and is left out, so an image-only hold overlapping the blob
 * is not emitted twice.
 */
object HoldSplit {
    private const val MIN_SIDE = 0.04f
    private const val HUE_TOL = 30f
    private const val MIN_SHARE = 0.04f // and at least MIN_SIDE² of pixels: a small hold beside a big one still counts
    private const val MAX_POINTS = 96

    fun byColor(holds: List<Hold>, photo: Orthophoto, patches: ColorPatches, frame: WallFrame, wall: WallColor?): List<Hold> {
        val w = photo.image.width
        val minSidePx = max(2, (MIN_SIDE / photo.px).toInt())
        val areas = holds.map { it.width * it.tall }
        val owner = IntArray(w * photo.image.height) { -1 }
        for ((hi, hold) in holds.withIndex()) for (i in Raster.fillPolygon(polygon(hold), photo)) if (owner[i] < 0 || areas[hi] < areas[owner[i]]) owner[i] = hi
        var nextId = (holds.maxOfOrNull { it.id } ?: 0) + 1
        val out = ArrayList<Hold>()
        for ((hi, hold) in holds.withIndex()) {
            val inside = Raster.fillPolygon(polygon(hold), photo).filter { owner[it] == hi }.toIntArray()
            val groups = hueGroups(inside, patches)
            val total = groups.sumOf { it.size }
            val parts = groups.filter { g -> g.size >= MIN_SHARE * total && g.size >= minSidePx * minSidePx && longSide(g, w) >= minSidePx }
            if (parts.size < 2) { out.add(hold); continue }
            val others = HashSet<Int>()
            for (k in 1 until parts.size) others.addAll(parts[k])
            // The largest part keeps its own pixels and the grey, white and dark ones (not the wall showing through the old outline).
            val kept = inside.filter { i -> i !in others && (patches.label[i] != 0 || (wall != null && !wall.matches(patches.hue[i], patches.sat[i], patches.value[i]))) }
            out.add(partHold(hold.id, kept.toIntArray(), hold.voxels, photo, frame).also { it.role = hold.role; it.tape = hold.tape })
            for (k in 1 until parts.size) out.add(partHold(nextId++, parts[k].toIntArray(), 0, photo, frame))
        }
        return out
    }

    private fun polygon(hold: Hold): FloatArray = hold.outline.takeIf { it.size >= 6 } ?: Raster.ellipse(hold.u, hold.v, hold.width, hold.tall)

    /** A hold from [pixels] of the orthophoto: outline traced from them, colour their mean, height the relief under them. */
    private fun partHold(id: Int, pixels: IntArray, voxels: Int, photo: Orthophoto, frame: WallFrame): Hold {
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

    /**
     * Pixel index lists, one per hue group, largest first; pixels outside any patch are left out. Groups are
     * the peaks of the pixels' hue histogram (10° bins, smoothed) at least [HUE_TOL] apart; each pixel goes
     * to its nearest peak. Pixels, not patches, because a dark olive rim can link a green hold and a brown
     * volume into one patch whose mean hue says neither.
     */
    private fun hueGroups(inside: IntArray, patches: ColorPatches): List<List<Int>> {
        val bins = 36
        val binWidth = 360f / bins
        val hist = IntArray(bins)
        var total = 0
        for (i in inside) if (patches.label[i] != 0) { hist[(patches.hue[i] / binWidth).toInt().coerceIn(0, bins - 1)]++; total++ }
        if (total == 0) return emptyList()
        val smooth = FloatArray(bins) { b -> (hist[(b + bins - 1) % bins] + 2f * hist[b] + hist[(b + 1) % bins]) / 4f }
        val peaks = (0 until bins).filter { b -> smooth[b] > 0 && smooth[b] >= smooth[(b + bins - 1) % bins] && smooth[b] >= smooth[(b + 1) % bins] }
            .sortedByDescending { smooth[it] }
        val kept = ArrayList<Float>()
        for (b in peaks) {
            val hue = (b + 0.5f) * binWidth
            if (kept.none { ColorUtil.hueDistance(it, hue) < HUE_TOL }) kept.add(hue)
        }
        val groups = List(kept.size) { ArrayList<Int>() }
        for (i in inside) {
            if (patches.label[i] == 0) continue
            groups[kept.indices.minByOrNull { ColorUtil.hueDistance(kept[it], patches.hue[i]) }!!].add(i)
        }
        return groups.sortedByDescending { it.size }
    }

    private fun longSide(pixels: List<Int>, w: Int): Int {
        var x0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y0 = Int.MAX_VALUE; var y1 = Int.MIN_VALUE
        for (i in pixels) { val x = i % w; val y = i / w; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        return max(x1 - x0 + 1, y1 - y0 + 1)
    }

    private fun outlineOf(pixels: IntArray, photo: Orthophoto): FloatArray {
        val w = photo.image.width; val h = photo.image.height
        val cells = IntArray(pixels.size) { it }; val cxs = IntArray(pixels.size) { pixels[it] % w }; val cys = IntArray(pixels.size) { (h - 1) - pixels[it] / w }
        val outline = HoldOutline.trace(cells, cxs, cys, photo.px, MAX_POINTS, 3)
        for (i in 0 until outline.size / 2) { outline[i * 2] += photo.minU; outline[i * 2 + 1] += photo.maxV - h * photo.px }
        return outline
    }
}
