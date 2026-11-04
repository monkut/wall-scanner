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

    fun byColor(holds: List<Hold>, photo: Orthophoto, patches: ColorPatches, surface: WallSurface, wall: WallColor?): List<Hold> {
        val w = photo.image.width
        val minSidePx = max(2, (MIN_SIDE / photo.px).toInt())
        val owner = ImageHold.owners(holds, photo)
        var nextId = (holds.maxOfOrNull { it.id } ?: 0) + 1
        val out = ArrayList<Hold>()
        for ((hi, hold) in holds.withIndex()) {
            val inside = Raster.fillPolygon(ImageHold.polygon(hold), photo).filter { owner[it] == hi }.toIntArray()
            val groups = hueGroups(inside, patches)
            val total = groups.sumOf { it.size }
            val parts = groups.filter { g -> g.size >= MIN_SHARE * total && g.size >= minSidePx * minSidePx && longSide(g, w) >= minSidePx }
            if (parts.size < 2) { out.add(hold); continue }
            val others = HashSet<Int>()
            for (k in 1 until parts.size) others.addAll(parts[k])
            // The largest part keeps its own pixels and the grey, white and dark ones (not the wall showing through the old outline).
            val kept = inside.filter { i -> i !in others && (patches.label[i] != 0 || (wall != null && !wall.matches(patches.hue[i], patches.sat[i], patches.value[i]))) }
            out.add(ImageHold.fromPixels(hold.id, kept.toIntArray(), hold.voxels, photo, surface).also { it.role = hold.role; it.tape = hold.tape })
            for (k in 1 until parts.size) out.add(ImageHold.fromPixels(nextId++, parts[k].toIntArray(), 0, photo, surface))
        }
        return out
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
}
