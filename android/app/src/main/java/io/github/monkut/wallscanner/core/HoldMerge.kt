package io.github.monkut.wallscanner.core

/**
 * Brings the hold model's masks ([SegDecoder.holds]) together with the holds from depth and the photo, and
 * drops outlines of the wall's edge and of bare wall that nothing says are holds.
 */
object HoldMerge {
    private const val INSIDE = 0.7f      // a mask this much inside a hold is part of it …
    private const val MIN_PART = 0.1f    // … when it is at least this share of the hold's area
    private const val DUPLICATE = 0.5f   // a part this much inside a larger part is the same hold
    private const val TOUCH = 0.35f      // parts sharing this much of the smaller one's outline are one hold
    private const val TOUCH_PX = 2       // … within this many pixels
    private const val MAX_COVERED = 0.3f // a mask this covered by the holds is one of them
    private const val EDGE_M = 0.05f     // the edge band: within this of the wall extent's edge, or past it
    private const val EDGE_SHARE = 0.5f  // a hold this much in the band is an edge hold
    private const val MIN_COLOURED = 0.3f // a hold stays when this share of it has a hold's colour …
    private const val MIN_SUPPORT = 0.5f  // … or the model's masks cover this share of it
    private const val MAX_WALL = 0.5f     // a hold this much the wall's colour is bare wall unless vouched for
    private const val MAX_TAPE = 0.5f     // a hold this much covered by the model's tape strips is tape

    class Merged(val holds: List<Hold>, val split: Int, val added: Int)

    /**
     * [holds] with the model's [masks] merged in. A hold that contains two or more distinct masks (each at
     * least [INSIDE] inside it and [MIN_PART] of its area) is neighbouring holds the relief or a shared colour
     * joined, typically big holds and volumes of one colour, and is replaced by those masks; the largest keeps
     * the hold's id, role, tape and voxels. Masks that share a long stretch of outline ([touching]) stay one
     * hold: the model cuts a hold in two at a seam between keyframes or where a two-tone hold changes colour,
     * while separate holds have wall between them. Masks the result covers less than [MAX_COVERED] are added.
     */
    fun withModel(holds: List<Hold>, masks: List<Hold>, photo: Orthophoto, frame: WallFrame): Merged {
        if (masks.isEmpty()) return Merged(holds, 0, 0)
        val w = photo.image.width; val h = photo.image.height
        val n = w * h
        val maskPx = masks.map { Raster.fillPolygon(ImageHold.polygon(it), photo) }
        val inHold = BooleanArray(n)
        val used = HashSet<Int>()
        val out = ArrayList<Hold>()
        var split = 0
        for (hold in holds) {
            val px = Raster.fillPolygon(ImageHold.polygon(hold), photo)
            for (i in px) inHold[i] = true
            val inside = masks.indices.filter { k ->
                k !in used && maskPx[k].size >= MIN_PART * px.size && maskPx[k].count { inHold[it] } >= INSIDE * maskPx[k].size
            }.sortedByDescending { maskPx[it].size }
            for (i in px) inHold[i] = false
            val parts = ArrayList<Int>()
            for (k in inside) {
                val mine = maskPx[k].toHashSet()
                if (parts.none { j -> maskPx[j].count { it in mine } >= DUPLICATE * maskPx[k].size }) parts.add(k)
            }
            val groups = ArrayList<MutableList<Int>>()
            for (k in parts) groups.firstOrNull { g -> g.any { touching(maskPx[it], maskPx[k], w, h) } }?.add(k) ?: groups.add(mutableListOf(k))
            if (groups.size < 2) { out.add(hold); continue }
            split++
            used.addAll(parts)
            for ((gi, g) in groups.withIndex()) {
                val first = masks[g[0]]
                val part = when {
                    gi > 0 && g.size == 1 -> first
                    g.size == 1 -> Hold(hold.id, first.cx, first.cy, first.cz, first.u, first.v, first.width, first.tall, first.height, first.r, first.g, first.b, hold.voxels, first.outline)
                    else -> ImageHold.fromPixels(if (gi == 0) hold.id else first.id, g.flatMap { maskPx[it].asList() }.distinct().toIntArray(), if (gi == 0) hold.voxels else 0, photo, frame)
                }
                if (gi == 0) { part.role = hold.role; part.tape = hold.tape }
                out.add(part)
            }
        }
        val covered = BooleanArray(n)
        for (hold in out) for (i in Raster.fillPolygon(ImageHold.polygon(hold), photo)) covered[i] = true
        val added = masks.indices.filter { k -> k !in used && maskPx[k].count { covered[it] } < MAX_COVERED * maskPx[k].size }
        return Merged(out + added.map { masks[it] }, split, added.size)
    }

    /**
     * [holds] without the outlines nothing vouches for. The photo reaches past the wall surface depth found,
     * and there the mat, concrete, beams and the next wall face make outlines; inside it, depth noise and
     * concrete make outlines of bare wall. Real holds are coloured or seen by the model, so a hold that is
     * neither ([MIN_COLOURED] of it with a hold's colour ([ColorPatches.offWallHue]), [MIN_SUPPORT] of it
     * covered by the [masks]) is dropped when it lies at the edge (at least [EDGE_SHARE] within [EDGE_M] of
     * the [extent]'s edge, or past it) or is mostly the wall's own colour ([MAX_WALL] of it). Without a wall
     * colour those cannot be judged, and stay. A hold the model's [tape] strips cover by [MAX_TAPE] is tape,
     * whatever else says so: the photo search and the colour test both take coloured tape for a hold.
     */
    fun prune(holds: List<Hold>, masks: List<Hold>, tape: List<Hold>, photo: Orthophoto, patches: ColorPatches, wall: WallColor?, extent: FloatArray): List<Hold> {
        val w = photo.image.width
        val onTape = BooleanArray(w * photo.image.height)
        for (t in tape) for (i in Raster.fillPolygon(ImageHold.polygon(t), photo)) onTape[i] = true
        val supported = BooleanArray(w * photo.image.height)
        for (m in masks) for (i in Raster.fillPolygon(ImageHold.polygon(m), photo)) supported[i] = true
        return holds.filter { hold ->
            val px = Raster.fillPolygon(ImageHold.polygon(hold), photo)
            if (px.isEmpty()) return@filter wall == null // wholly off the photo: past the wall's edge
            if (px.count { onTape[it] } >= MAX_TAPE * px.size) return@filter false
            if (wall == null) return@filter true
            val vouched = px.count { ColorPatches.offWallHue(patches.hue[it], patches.sat[it], patches.value[it], wall) } >= MIN_COLOURED * px.size ||
                px.count { supported[it] } >= MIN_SUPPORT * px.size
            if (vouched) return@filter true
            val edge = px.count { i ->
                val u = photo.u(i % w + 0.5f); val v = photo.v(i / w + 0.5f)
                minOf(u - extent[0], extent[2] - u, v - extent[1], extent[3] - v) < EDGE_M
            }
            edge < EDGE_SHARE * px.size && px.count { wall.matches(patches.hue[it], patches.sat[it], patches.value[it]) } < MAX_WALL * px.size
        }
    }

    /** Whether the smaller of two pixel sets has at least [TOUCH] of its outline pixels within [TOUCH_PX] of the other. */
    private fun touching(a: IntArray, b: IntArray, w: Int, h: Int): Boolean {
        val (small, other) = if (a.size <= b.size) a to b else b to a
        val inSmall = small.toHashSet()
        val near = HashSet<Int>()
        for (i in other) {
            val x = i % w; val y = i / w
            for (dy in -TOUCH_PX..TOUCH_PX) for (dx in -TOUCH_PX..TOUCH_PX) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) near.add(ny * w + nx)
            }
        }
        var edge = 0; var shared = 0
        for (i in small) {
            val x = i % w; val y = i / w
            val inner = x > 0 && y > 0 && x < w - 1 && y < h - 1 && (i - 1) in inSmall && (i + 1) in inSmall && (i - w) in inSmall && (i + w) in inSmall
            if (inner) continue
            edge++
            if (i in near) shared++
        }
        return edge > 0 && shared >= TOUCH * edge
    }
}
