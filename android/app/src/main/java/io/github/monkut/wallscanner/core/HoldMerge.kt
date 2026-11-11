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
    private const val NESTED_INSIDE = 0.9f // a hold with this much of its outline inside another's is a piece of it (#29 B) …
    private const val NESTED_MIN_SHARE = 0.2f // … when it is this much of the host's area (a volume's face, not a hold mounted on a volume:
    private const val NESTED_MAX_DE = 25f     // 3% and 1% of their hosts in the Oct 21 and Oct 23 references) and about its colour
    private const val ON_STEP_SHARE = 0.4f // an image-only hold with this much of itself on a step of the wall is the step's paint or wood (#29 A):
    // the 1F painted edge sits 50% on its step; the real green foothold at the foot of the Main wall's hangboard 36%
    private const val STRIP_MAX_WIDTH = 0.03f // an outline narrower than this …
    private const val STRIP_MIN_ASPECT = 6f   // … and this many times as long is a strip, not a hold

    class Merged(val holds: List<Hold>, val split: Int, val added: Int)

    /**
     * [holds] with the model's [masks] merged in. A hold that contains two or more distinct masks (each at
     * least [INSIDE] inside it and [MIN_PART] of its area) is neighbouring holds the relief or a shared colour
     * joined, typically big holds and volumes of one colour, and is replaced by those masks; the largest keeps
     * the hold's id, role, tape and voxels. Masks that share a long stretch of outline ([touching]) stay one
     * hold: the model cuts a hold in two at a seam between keyframes or where a two-tone hold changes colour,
     * while separate holds have wall between them. Masks the result covers less than [MAX_COVERED] are added.
     */
    fun withModel(holds: List<Hold>, masks: List<Hold>, photo: Orthophoto, surface: WallSurface): Merged {
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
                    else -> ImageHold.fromPixels(if (gi == 0) hold.id else first.id, g.flatMap { maskPx[it].asList() }.distinct().toIntArray(), if (gi == 0) hold.voxels else 0, photo, surface)
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

    /**
     * [holds] with nested outlines merged (#29 B): a hold whose pixels lie at least [NESTED_INSIDE] inside a
     * larger hold's outline, of about its colour and at least [NESTED_MIN_SHARE] of its area, is a piece of it
     * (the depth found a volume's upper face, the photo search the whole volume). A small hold mounted on a
     * volume stays its own. The larger keeps its record and outline, takes the voxels, and the role and tape
     * of either. Runs on the final outlines, after SAM: SamRefine merges only pieces it redrew itself.
     */
    fun nested(holds: List<Hold>, photo: Orthophoto): List<Hold> {
        val pixels = holds.map { Raster.fillPolygon(ImageHold.polygon(it), photo) }
        val sets = HashMap<Int, HashSet<Int>>() // a host's pixels, built when a hold of its colour and size asks
        val lab = holds.map { ColorUtil.rgbToLab(it.r, it.g, it.b) }
        val into = IntArray(holds.size) { -1 }
        for (i in holds.indices.sortedBy { pixels[it].size }) {
            val px = pixels[i]
            if (px.isEmpty()) continue
            val host = holds.indices.filter { it != i && into[it] < 0 && pixels[it].size > px.size && px.size >= NESTED_MIN_SHARE * pixels[it].size && ColorUtil.deltaE(lab[i], lab[it]) <= NESTED_MAX_DE }
                .firstOrNull { j -> val set = sets.getOrPut(j) { pixels[j].toHashSet() }; px.count { it in set } >= NESTED_INSIDE * px.size }
            if (host != null) into[i] = host
        }
        // A piece of a piece goes to the outermost host.
        for (i in holds.indices) while (into[i] >= 0 && into[into[i]] >= 0) into[i] = into[into[i]]
        return holds.indices.filter { into[it] < 0 }.map { i ->
            val members = listOf(i) + holds.indices.filter { into[it] == i }
            val h = holds[i]
            if (members.size == 1) return@map h
            // The host as it was (depth colour, height, centre and outline), with the pieces' voxels and marks.
            Hold(h.id, h.cx, h.cy, h.cz, h.u, h.v, h.width, h.tall, h.height, h.r, h.g, h.b, members.sumOf { holds[it].voxels }, h.outline).also {
                it.role = members.map { m -> holds[m].role }.firstOrNull { r -> r != HoldRole.ANY } ?: HoldRole.ANY
                it.tape = members.firstNotNullOfOrNull { m -> holds[m].tape }
            }
        }
    }

    /**
     * [holds] without the image-only ones that lie on a step of the wall (#28, #29 A): at least [ON_STEP_SHARE]
     * of the hold's pixels inside a step outline (the step's rim and riser as the depth saw them). The photo
     * search takes the step's painted edge and bare wood for holds; a hold with voxels was seen by depth as a
     * bump of its own and stays. A hold mounted on the step's rim goes with it: the step is not a face yet (#8).
     */
    fun dropOnSteps(holds: List<Hold>, steps: List<FloatArray>, photo: Orthophoto): List<Hold> {
        if (steps.isEmpty()) return holds
        val onStep = BooleanArray(photo.image.width * photo.image.height)
        for (s in steps) for (i in Raster.fillPolygon(s, photo)) onStep[i] = true
        return holds.filter { hold ->
            if (hold.voxels > 0) return@filter true
            val px = Raster.fillPolygon(ImageHold.polygon(hold), photo)
            px.isEmpty() || px.count { onStep[it] } < ON_STEP_SHARE * px.size
        }
    }

    /**
     * [holds] without strips: outlines under [STRIP_MAX_WIDTH] across and at least [STRIP_MIN_ASPECT] times as
     * long, the edge of a column or panel past the wall (2.5 × 53 cm on Oct 29 5:22 PM). Real holds in the four
     * references are at least 4 cm across and at most 4.7 times as long as wide. Runs on the final outlines:
     * SAM can redraw a hold into a strip after [prune].
     */
    fun dropStrips(holds: List<Hold>): List<Hold> = holds.filter { hold ->
        val thin = minOf(hold.width, hold.tall)
        thin >= STRIP_MAX_WIDTH || maxOf(hold.width, hold.tall) < STRIP_MIN_ASPECT * thin
    }

    private const val CUT_M = 0.005f // one orthophoto pixel: an outline this close to the photo's border runs off it

    /**
     * [holds] without the ones the scan cut off ([isCut]). A hold not wholly in the scan belongs to a route beside
     * the one scanned: it is neither numbered nor grouped into a route (1F 2026-11-10 17:49 #1, #11, #24, #28 at
     * the right edge). Runs before the holds are numbered and before the start marks.
     */
    fun dropCut(holds: List<Hold>, photo: Orthophoto): List<Hold> = holds.filterNot { isCut(it, photo) }

    /** Whether [hold]'s outline reaches within [CUT_M] of the [photo]'s border: the hold runs past what the scan saw. */
    fun isCut(hold: Hold, photo: Orthophoto): Boolean {
        val o = hold.outline
        return (0 until o.size / 2).any { minOf(o[2 * it] - photo.minU, photo.maxU - o[2 * it], o[2 * it + 1] - photo.minV, photo.maxV - o[2 * it + 1]) < CUT_M }
    }

    // Tape left as a hold: a flat strip, under TAPE_MAX_SHORT across (the climber's labels: 4.5–6.4 cm) …
    private const val TAPE_MAX_SHORT = 0.07f
    private const val TAPE_MIN_ASPECT = 1.5f  // … at least this many times as long (1.7–2.4) …
    private const val TAPE_MIN_FILL = 0.75f   // … filling this much of its smallest enclosing rectangle (0.78–0.86; holds without depth: median 0.74) …
    private const val TAPE_MAX_SHADE = 0.18f  // … and evenly lit: brightness spread, std over mean (0.08–0.17; holds without depth: median 0.22)

    /**
     * [holds] without the image-only ones shaped and lit like tape ([isTapeShaped]). The hold model's tape class
     * removes most tape in [prune]; strips it misses reach the final outlines as holds (Main wall 2026-11-10 #27
     * and #75, 1F 2026-11-05 #19). Runs before the start marks, so a tape strip is never a start hold.
     */
    fun dropTapeShaped(holds: List<Hold>, photo: Orthophoto, extent: FloatArray): List<Hold> = holds.filterNot { isTapeShaped(it, photo, extent) }

    /**
     * Whether [hold] is a tape strip: no depth points (tape is flat; the depth is too coarse to say so for small
     * holds, but a hold it did see is no tape), a narrow rectangle (narrow side, length over width, and how much of
     * its smallest enclosing rectangle the outline fills) of one even brightness, where a hold shows its shading.
     * A hold whose outline reaches within [EDGE_M] of the [extent]'s edge stays: the scan cut it, and the cut is
     * its straight side (Main wall 2026-11-10 blue #100 at the top edge, blue #49 at the left).
     * On the seven labelled scans: all three tape strips and no hold. Tuned on three strips: the margins are thin
     * (shading 0.17 against 0.18, fill 0.78 against 0.75), so a small one-colour hold the depth missed can go with
     * them; the drop is logged (`hold tape:`).
     */
    fun isTapeShaped(hold: Hold, photo: Orthophoto, extent: FloatArray): Boolean {
        if (hold.voxels > 0 || hold.outline.size < 6) return false
        val o = hold.outline
        if ((0 until o.size / 2).any { minOf(o[2 * it] - extent[0], extent[2] - o[2 * it], o[2 * it + 1] - extent[1], extent[3] - o[2 * it + 1]) < EDGE_M }) return false
        val (short, long, rectArea) = smallestRectangle(hold.outline) ?: return false
        if (short > TAPE_MAX_SHORT || long < TAPE_MIN_ASPECT * short || polygonArea(hold.outline) < TAPE_MIN_FILL * rectArea) return false
        val source = photo.source
        var n = 0; var sum = 0.0; var sumSq = 0.0
        for (i in Raster.fillPolygon(hold.outline, photo)) {
            if (source != null && source[i] < 0) continue // no keyframe saw it: black, not the strip's colour
            val c = photo.image.pixels[i]
            val v = maxOf((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff) / 255.0
            n++; sum += v; sumSq += v * v
        }
        if (n < 5) return false
        val mean = sum / n
        if (mean <= 0.0) return false
        return kotlin.math.sqrt(maxOf(0.0, sumSq / n - mean * mean)) <= TAPE_MAX_SHADE * mean
    }

    /** Area of a polygon given as (u, v) pairs. */
    private fun polygonArea(p: FloatArray): Float {
        var a = 0f
        for (i in 0 until p.size / 2) { val j = (i + 1) % (p.size / 2); a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1] }
        return kotlin.math.abs(a) / 2
    }

    /** The smallest rectangle around a polygon's (u, v) points, as (narrow side, long side, area); one edge lies on the convex hull. */
    private fun smallestRectangle(p: FloatArray): Triple<Float, Float, Float>? {
        val pts = (0 until p.size / 2).map { p[2 * it] to p[2 * it + 1] }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (pts.size < 3) return null
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) = (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val hull = ArrayList<Pair<Float, Float>>()
        for (pass in 0..1) {
            val start = hull.size
            for (q in if (pass == 0) pts else pts.asReversed()) {
                while (hull.size >= start + 2 && cross(hull[hull.size - 2], hull[hull.size - 1], q) <= 0) hull.removeAt(hull.size - 1)
                hull.add(q)
            }
            hull.removeAt(hull.size - 1)
        }
        var best: Triple<Float, Float, Float>? = null
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]
            val len = kotlin.math.hypot(b.first - a.first, b.second - a.second)
            if (len == 0f) continue
            val ux = (b.first - a.first) / len; val uy = (b.second - a.second) / len
            var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE; var minB = Float.MAX_VALUE; var maxB = -Float.MAX_VALUE
            for ((x, y) in pts) { val s = x * ux + y * uy; val t = -x * uy + y * ux; minA = minOf(minA, s); maxA = maxOf(maxA, s); minB = minOf(minB, t); maxB = maxOf(maxB, t) }
            val w = maxA - minA; val h = maxB - minB
            if (best == null || w * h < best.third) best = Triple(minOf(w, h), maxOf(w, h), w * h)
        }
        return best
    }

    private const val DEPTH_MAX_DE = 25f      // pieces of one hold are about one colour (CIE ΔE of the medians) …
    private const val DEPTH_MIN_HEIGHT = 0.015f // … both stand off the wall (75th percentile of relief) above the depth noise …
    private const val DEPTH_RIDGE = 0.005f    // … and their shared edge is this much higher than the lower piece: no valley
    private const val DEPTH_TOUCH_PX = 2

    /**
     * [holds] with touching pieces of one object joined, judged by depth: two holds of about one colour, both
     * standing off the wall, whose shared edge lies at least [DEPTH_RIDGE] above the lower of them are one hold
     * (a volume's two faces, a hold's lit horn and its base); separate holds meet in a valley. Colour alone cannot
     * tell (two red volumes side by side differ by ΔE 2, one blue hold's horn and base by 11–22). On Oct 23 2:04 PM
     * this joins exactly the four pairs the climber named and none of the 19 other touching pairs. The joined hold
     * keeps the id of the piece with most voxels, sums the voxels, takes the first role and tape.
     */
    fun byDepth(holds: List<Hold>, photo: Orthophoto, surface: WallSurface): List<Hold> {
        val w = photo.image.width; val h = photo.image.height
        val px = holds.map { Raster.fillPolygon(ImageHold.polygon(it), photo) }
        val height = px.map { p -> if (p.isEmpty()) 0f else FloatArray(p.size) { photo.relief[p[it]] }.also { it.sort() }[p.size * 3 / 4] }
        val lab = px.map { p -> medianLab(p, photo.image) }
        val owner = IntArray(w * h) { -1 }
        for ((k, p) in px.withIndex()) for (i in p) owner[i] = k
        // Pixels within DEPTH_TOUCH_PX of another hold: the shared edge of each touching pair.
        val edges = HashMap<Long, HashSet<Int>>()
        for ((k, p) in px.withIndex()) for (i in p) {
            val x = i % w; val y = i / w
            for (dy in -DEPTH_TOUCH_PX..DEPTH_TOUCH_PX) for (dx in -DEPTH_TOUCH_PX..DEPTH_TOUCH_PX) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val o = owner[ny * w + nx]
                if (o >= 0 && o != k) edges.getOrPut(minOf(k, o).toLong() * holds.size + maxOf(k, o)) { HashSet() }.add(i)
            }
        }
        val parent = IntArray(holds.size) { it }
        fun find(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        for ((key, edge) in edges) {
            val a = (key / holds.size).toInt(); val b = (key % holds.size).toInt()
            val lower = minOf(height[a], height[b])
            if (lower < DEPTH_MIN_HEIGHT || ColorUtil.deltaE(lab[a], lab[b]) > DEPTH_MAX_DE) continue
            val ridge = edge.map { photo.relief[it] }.sorted()[edge.size / 2]
            if (ridge - lower >= DEPTH_RIDGE) parent[find(a)] = find(b)
        }
        val groups = holds.indices.groupBy { find(it) }.values
        if (groups.size == holds.size) return holds
        val joined = HashMap<Int, Hold>()
        for (g in groups) {
            if (g.size == 1) continue
            val primary = g.maxBy { holds[it].voxels }
            val pixels = g.flatMap { px[it].asList() }.distinct().toIntArray()
            joined[primary] = ImageHold.fromPixels(holds[primary].id, pixels, g.sumOf { holds[it].voxels }, photo, surface).also { hold ->
                hold.role = (listOf(primary) + g).map { holds[it].role }.firstOrNull { it != HoldRole.ANY } ?: HoldRole.ANY
                hold.tape = (listOf(primary) + g).firstNotNullOfOrNull { holds[it].tape }
            }
        }
        return holds.indices.mapNotNull { i -> joined[i] ?: holds[i].takeIf { groups.first { i in it }.size == 1 } }
    }

    private fun medianLab(pixels: IntArray, image: Image): FloatArray {
        if (pixels.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        val lab = pixels.map { val p = image.pixels[it]; ColorUtil.rgbToLab(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff) }
        return FloatArray(3) { c -> lab.map { it[c] }.sorted()[lab.size / 2] }
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
