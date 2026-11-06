package io.github.monkut.wallscanner.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** A detected hold: world center, wall-plane coordinates (u right, v up), extents and mean color. */
class Hold(
    val id: Int,
    val cx: Float, val cy: Float, val cz: Float,
    val u: Float, val v: Float,
    val width: Float, val tall: Float, val height: Float,
    val r: Int, val g: Int, val b: Int,
    val voxels: Int,
    /**
     * Closed outline in the wall plane, (u, v) interleaved: traced from the relief cells (HoldOutline) and
     * replaced by the orthophoto shape when one is available (HoldShapes). Empty for holds from older scenes.
     */
    var outline: FloatArray = FloatArray(0),
) {
    /** Primary route (first of [routeIds]); -1 when unassigned. */
    var routeId: Int = -1
    /** All routes this hold belongs to (tape and free walls share holds between routes). */
    val routeIds: MutableList<Int> = ArrayList()
    var role: HoldRole = HoldRole.ANY
    /** Colour (0xRRGGBB) of the start tape running from this hold to its placard; null when none was seen. The gym's tape colour is the grade. */
    var tape: Int? = null
    val hsv: FloatArray = ColorUtil.rgbToHsv(r, g, b)
    val hue: Float get() = hsv[0]
    val sat: Float get() = hsv[1]
    val value: Float get() = hsv[2]
}

/** Holds found, and the outlines in (u, v) of the steps of the wall that were set aside (#28). */
class Detection(val holds: List<Hold>, val steps: List<FloatArray>)

/**
 * Finds holds as bumps in a 2-D relief map of the wall.
 *
 * Raw ARCore depth leaves the scanned wall surface 2–3 cm thick, so clustering voxels by their own plane
 * distance merges holds with the speckle around them. Instead the wall is binned into [Tuning.CLUSTER_CELL]
 * cells in (u, v); each cell takes the median plane distance of its voxels, the map is smoothed 3 × 3, and
 * holds are 8-connected components of cells between [Tuning.MIN_HOLD_HEIGHT] and [Tuning.MAX_HOLD_HEIGHT].
 * Neighbouring holds whose skirts touch are separated by re-thresholding each component at the rising
 * [Tuning.HOLD_SPLIT_LEVELS]: where a component splits into two or more prominent peaks, the peaks become
 * separate holds and the component's remaining cells go to the nearest peak. A hold's colour and centre come
 * from the voxels of its core (cells at least half as high as its peak), which keeps wall colour bled into
 * the skirt out of the estimate. Finally a coloured hold's footprint grows into neighbouring cells of the
 * same colour: flat slopers and the thin edges of jugs sit at the depth noise floor, where relief alone sees
 * only part of the hold, but their colour still marks them.
 */
object HoldDetector {
    /**
     * [localRelief] measures hold height against the local rock surface (per-cell median of the plane
     * distance, bilinearly interpolated) instead of the plane itself; used for outdoor rock, which is not flat.
     * Voxels matching [wall] are left out of a hold's colour when enough other voxels remain. [wallExtent] is the
     * wall's area in (u, v) as [minU, minV, maxU, maxV]; outside it the mat and the beams stand off the plane.
     */
    fun detect(cloud: VoxelCloud, surface: WallSurface, localRelief: Boolean = false, wall: WallColor? = null, wallExtent: FloatArray? = null): Detection {
        val n = cloud.count
        val xyz = cloud.xyz
        // Every voxel measured against the face it lies on, in the unfolded (u, v).
        val us = FloatArray(n); val vs = FloatArray(n); val relief = FloatArray(n)
        val p = FloatArray(3)
        for (i in 0 until n) { surface.project(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], p); us[i] = p[0]; vs[i] = p[1]; relief[i] = p[2] }
        if (localRelief) subtractLocalSurface(relief, us, vs)
        val grid = Grid.build(cloud, us, vs, relief)
        if (grid.count == 0) return Detection(emptyList(), emptyList())

        val all = IntArray(grid.count) { it }
        val finals = ArrayList<IntArray>()
        val steps = ArrayList<IntArray>()
        for (comp in grid.components(all, Tuning.MIN_HOLD_HEIGHT, Tuning.MAX_HOLD_HEIGHT)) {
            if (comp.size < Tuning.MIN_HOLD_CELLS) continue
            val pieces = grid.split(comp, 0)
            // A wide piece that rises past any hold's height inside the wall's area is the rim of a step of the wall
            // (a boxed-out section, a hangboard panel), not a hold on it (#28). A bottom hold's component may take
            // in the mat, which is as high, but the mat lies outside the extent. The step's cells are claimed so
            // that no hold of its colour grows into it. Scraps of its riser under STEP_MIN_EXTENT come through.
            for (piece in pieces) {
                if (grid.extent(piece) >= Tuning.STEP_MIN_EXTENT && grid.peakInside(piece, wallExtent) >= Tuning.MAX_HOLD_HEIGHT) steps.add(piece) else finals.add(piece)
            }
        }
        for (piece in steps) for (c in piece) grid.claimed[c] = true
        // The mat passes the same test where it enters the extent at the bottom; it is claimed like a step but
        // not reported as one, so that image-only holds along the bottom row are not taken for its paint.
        val reported = if (wallExtent == null) steps else steps.filter { piece -> piece.all { (grid.cv[it] + 0.5f) * Tuning.CLUSTER_CELL > wallExtent[1] + Tuning.STEP_BOTTOM_MARGIN } }

        // Pass 1: accept components and measure their cores, claiming their cells so growth cannot cross holds.
        val candidates = ArrayList<Candidate>()
        val members = IntArray(n)
        for (comp in finals) {
            if (comp.size < Tuning.MIN_HOLD_CELLS) continue
            val extent = grid.extent(comp)
            if (extent < Tuning.MIN_HOLD_EXTENT || extent > Tuning.MAX_HOLD_EXTENT) continue
            var peak = -Float.MAX_VALUE
            for (c in comp) if (grid.median[c] > peak) peak = grid.median[c]
            // Cells above MAX_HOLD_HEIGHT are already outside the component; a deep jug's own cells may still
            // carry a median above it from flying pixels, so the raw peak is reported but never rejected.
            if (peak < Tuning.HOLD_MIN_PEAK) continue

            val coreLevel = max(Tuning.MIN_HOLD_HEIGHT, Tuning.HOLD_CORE_FRACTION * peak)
            var m = 0
            var sx = 0.0; var sy = 0.0; var sz = 0.0
            for (c in comp) {
                if (grid.relief[c] < coreLevel) continue
                for (j in grid.start[c] until grid.start[c + 1]) {
                    val i = grid.order[j]
                    if (relief[i] < Tuning.MIN_HOLD_HEIGHT) continue
                    members[m++] = i
                    sx += xyz[i * 3]; sy += xyz[i * 3 + 1]; sz += xyz[i * 3 + 2]
                }
            }
            if (m < Tuning.MIN_HOLD_VOXELS) continue
            val color = holdColor(cloud.rgb, members, m, wall)
            candidates.add(Candidate(comp, m, color, peak, (sx / m).toFloat(), (sy / m).toFloat(), (sz / m).toFloat()))
            for (c in comp) grid.claimed[c] = true
        }

        // Pass 2: grow coloured holds along their colour, then build the records.
        val holds = ArrayList<Hold>()
        for (cand in candidates) {
            val cells = grid.growByColor(cand.cells, cand.color, wall)
            var minU = Int.MAX_VALUE; var maxU = Int.MIN_VALUE; var minV = Int.MAX_VALUE; var maxV = Int.MIN_VALUE
            for (c in cells) {
                if (grid.cu[c] < minU) minU = grid.cu[c]; if (grid.cu[c] > maxU) maxU = grid.cu[c]
                if (grid.cv[c] < minV) minV = grid.cv[c]; if (grid.cv[c] > maxV) maxV = grid.cv[c]
            }
            holds.add(
                Hold(
                    id = holds.size + 1,
                    cx = cand.cx, cy = cand.cy, cz = cand.cz,
                    u = surface.u(cand.cx, cand.cy, cand.cz), v = surface.v(cand.cx, cand.cy, cand.cz),
                    width = (maxU - minU + 1) * Tuning.CLUSTER_CELL, tall = (maxV - minV + 1) * Tuning.CLUSTER_CELL, height = cand.peak,
                    r = cand.color[0], g = cand.color[1], b = cand.color[2],
                    voxels = cand.voxels,
                    outline = HoldOutline.trace(cells, grid.cu, grid.cv, Tuning.CLUSTER_CELL),
                )
            )
        }
        holds.sortWith(compareBy({ it.v }, { it.u }))
        return Detection(
            holds.mapIndexed { index, h -> Hold(index + 1, h.cx, h.cy, h.cz, h.u, h.v, h.width, h.tall, h.height, h.r, h.g, h.b, h.voxels, h.outline) },
            reported.map { HoldOutline.trace(it, grid.cu, grid.cv, Tuning.CLUSTER_CELL) },
        )
    }

    private class Candidate(val cells: IntArray, val voxels: Int, val color: IntArray, val peak: Float, val cx: Float, val cy: Float, val cz: Float)

    /**
     * Relief map of the wall: one cell per [Tuning.CLUSTER_CELL] square in (u, v) that holds at least one
     * voxel within [Tuning.HOLD_RELIEF_MIN]..[Tuning.HOLD_RELIEF_MAX] of the plane. [order] lists voxel
     * indices grouped by cell; cell c owns `order[start[c] until start[c + 1]]`.
     */
    private class Grid(
        val count: Int,
        val cu: IntArray, val cv: IntArray,
        private val index: HashMap<Long, Int>,
        /** Median plane distance of the cell's voxels. */
        val median: FloatArray,
        /** [median] averaged over the cell and its present 8 neighbours. */
        val relief: FloatArray,
        /** Hue, saturation, value of the cell's hold-surface voxels (relief ≥ 1 cm), else of all its voxels. */
        val hsv: FloatArray,
        val start: IntArray, val order: IntArray,
    ) {
        private val eligible = BooleanArray(count)
        private val label = IntArray(count) { -1 }
        /** Cells that belong to an accepted hold; growth never enters them. */
        val claimed = BooleanArray(count)

        fun neighbour(c: Int, dx: Int, dy: Int): Int = index[VoxelKey.pack(cu[c] + dx, cv[c] + dy, 0)] ?: -1

        /** Larger side of the cells' bounding box, in metres. */
        fun extent(cells: IntArray): Float {
            var minU = Int.MAX_VALUE; var maxU = Int.MIN_VALUE; var minV = Int.MAX_VALUE; var maxV = Int.MIN_VALUE
            for (c in cells) {
                if (cu[c] < minU) minU = cu[c]; if (cu[c] > maxU) maxU = cu[c]
                if (cv[c] < minV) minV = cv[c]; if (cv[c] > maxV) maxV = cv[c]
            }
            return max(maxU - minU + 1, maxV - minV + 1) * Tuning.CLUSTER_CELL
        }

        /** 8-connected components of the cells in [cells] whose smoothed relief lies in [low, high]. */
        fun components(cells: IntArray, low: Float, high: Float = Float.MAX_VALUE): List<IntArray> {
            for (c in cells) eligible[c] = relief[c] >= low && relief[c] <= high
            val out = ArrayList<IntArray>()
            val queue = IntArray(cells.size)
            for (seed in cells) {
                if (!eligible[seed]) continue
                eligible[seed] = false
                var head = 0; var tail = 0
                queue[tail++] = seed
                while (head < tail) {
                    val x = queue[head++]
                    for (dx in -1..1) for (dy in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nb = neighbour(x, dx, dy)
                        if (nb >= 0 && eligible[nb]) { eligible[nb] = false; queue[tail++] = nb }
                    }
                }
                out.add(queue.copyOf(tail))
            }
            return out
        }

        /**
         * Splits [comp] (a component at level [li] of [Tuning.HOLD_SPLIT_LEVELS]) into its prominent peaks at
         * the higher levels, or returns it whole when it has at most one.
         */
        fun split(comp: IntArray, li: Int): List<IntArray> {
            val levels = Tuning.HOLD_SPLIT_LEVELS
            if (li + 1 >= levels.size) return listOf(comp)
            val next = levels[li + 1]
            val prominence = if (next < Tuning.HOLD_PROMINENCE_FRACTION_UNTIL) max(Tuning.HOLD_MIN_PROMINENCE, Tuning.HOLD_PROMINENCE_FRACTION * next)
            else Tuning.HOLD_PROMINENCE_HIGH
            val children = components(comp, next).filter { it.size >= Tuning.MIN_HOLD_CELLS && peak(it) - next >= prominence }
            if (children.isEmpty()) return listOf(comp)
            if (children.size == 1) {
                val deeper = split(children[0], li + 1)
                return if (deeper.size == 1) listOf(comp) else grow(deeper, comp)
            }
            val parts = ArrayList<IntArray>()
            for (child in children) parts.addAll(split(child, li + 1))
            return grow(parts, comp)
        }

        /**
         * Adds to [cells] every unclaimed neighbouring cell of the hold's [color] (hue within
         * [Tuning.HOLD_GROW_HUE_TOL], saturated, value within [Tuning.HOLD_GROW_VAL_TOL], not wall-coloured,
         * not behind the wall) within [Tuning.HOLD_GROW_MAX] of the hold's box. Grey, white and black holds are
         * left as relief found them: their colour does not set them apart from the wall or from each other.
         */
        fun growByColor(cells: IntArray, color: IntArray, wall: WallColor?): IntArray {
            val c = ColorUtil.rgbToHsv(color[0], color[1], color[2])
            if (c[1] < Tuning.MIN_ROUTE_SATURATION) return cells
            var minU = Int.MAX_VALUE; var maxU = Int.MIN_VALUE; var minV = Int.MAX_VALUE; var maxV = Int.MIN_VALUE
            for (k in cells) {
                if (cu[k] < minU) minU = cu[k]; if (cu[k] > maxU) maxU = cu[k]
                if (cv[k] < minV) minV = cv[k]; if (cv[k] > maxV) maxV = cv[k]
            }
            val reach = (Tuning.HOLD_GROW_MAX / Tuning.CLUSTER_CELL).toInt()
            var queue = cells.copyOf(cells.size * 4 + 64)
            var tail = cells.size
            var head = 0
            while (head < tail) {
                val x = queue[head++]
                for (dx in -1..1) for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nb = neighbour(x, dx, dy)
                    if (nb < 0 || claimed[nb]) continue
                    if (cu[nb] < minU - reach || cu[nb] > maxU + reach || cv[nb] < minV - reach || cv[nb] > maxV + reach) continue
                    if (relief[nb] < Tuning.HOLD_GROW_MIN_RELIEF || relief[nb] > Tuning.MAX_HOLD_HEIGHT) continue
                    val h = hsv[nb * 3]; val s = hsv[nb * 3 + 1]; val v = hsv[nb * 3 + 2]
                    if (s < Tuning.MIN_ROUTE_SATURATION || abs(v - c[2]) > Tuning.HOLD_GROW_VAL_TOL) continue
                    val dh = abs(((h - c[0]) % 360f + 540f) % 360f - 180f)
                    if (dh > Tuning.HOLD_GROW_HUE_TOL) continue
                    if (wall != null && wall.matches(h, s, v)) continue
                    claimed[nb] = true
                    if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
                    queue[tail++] = nb
                }
            }
            return queue.copyOf(tail)
        }

        /**
         * Highest cell median among [cells] and the cells next to them, inside [extent] (anywhere without one);
         * -∞ when none is. The neighbours count because a step's top, past any hold's height, lies just outside
         * the rim component that the relief window admits.
         */
        fun peakInside(cells: IntArray, extent: FloatArray?): Float {
            var best = -Float.MAX_VALUE
            for (c in cells) for (dx in -1..1) for (dy in -1..1) {
                val k = if (dx == 0 && dy == 0) c else neighbour(c, dx, dy)
                if (k < 0) continue
                if (extent != null) {
                    val u = (cu[k] + 0.5f) * Tuning.CLUSTER_CELL; val v = (cv[k] + 0.5f) * Tuning.CLUSTER_CELL
                    if (u < extent[0] || u > extent[2] || v < extent[1] || v > extent[3]) continue
                }
                if (median[k] > best) best = median[k]
            }
            return best
        }

        private fun peak(cells: IntArray): Float {
            var best = -Float.MAX_VALUE
            for (c in cells) if (relief[c] > best) best = relief[c]
            return best
        }

        /** Assigns every cell of [comp] outside [parts] to the nearest part (multi-source breadth-first search). */
        private fun grow(parts: List<IntArray>, comp: IntArray): List<IntArray> {
            for (c in comp) label[c] = 0
            val queue = IntArray(comp.size)
            var tail = 0
            for ((p, cells) in parts.withIndex()) for (c in cells) { label[c] = p + 1; queue[tail++] = c }
            var head = 0
            while (head < tail) {
                val x = queue[head++]
                for (dx in -1..1) for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nb = neighbour(x, dx, dy)
                    if (nb >= 0 && label[nb] == 0) { label[nb] = label[x]; queue[tail++] = nb }
                }
            }
            val sizes = IntArray(parts.size)
            for (c in comp) if (label[c] > 0) sizes[label[c] - 1]++
            val out = List(parts.size) { IntArray(sizes[it]) }
            val fill = IntArray(parts.size)
            for (c in comp) {
                val p = label[c] - 1
                if (p >= 0) out[p][fill[p]++] = c
                label[c] = -1
            }
            return out
        }

        companion object {
            fun build(cloud: VoxelCloud, us: FloatArray, vs: FloatArray, relief: FloatArray): Grid {
                val n = cloud.count
                val rgb = cloud.rgb
                val inv = 1f / Tuning.CLUSTER_CELL
                val index = HashMap<Long, Int>()
                var cu = IntArray(4096); var cv = IntArray(4096)
                var k = 0
                val cellOf = IntArray(n)
                for (i in 0 until n) {
                    if (relief[i] < Tuning.HOLD_RELIEF_MIN || relief[i] > Tuning.HOLD_RELIEF_MAX) { cellOf[i] = -1; continue }
                    val iu = floor(us[i] * inv).toInt()
                    val iv = floor(vs[i] * inv).toInt()
                    val key = VoxelKey.pack(iu, iv, 0)
                    var c = index[key]
                    if (c == null) {
                        if (k == cu.size) { cu = cu.copyOf(k * 2); cv = cv.copyOf(k * 2) }
                        cu[k] = iu; cv[k] = iv
                        index[key] = k
                        c = k++
                    }
                    cellOf[i] = c
                }
                val start = IntArray(k + 1)
                for (i in 0 until n) if (cellOf[i] >= 0) start[cellOf[i] + 1]++
                for (c in 0 until k) start[c + 1] += start[c]
                val order = IntArray(start[k])
                val cursor = start.copyOf(k)
                for (i in 0 until n) if (cellOf[i] >= 0) order[cursor[cellOf[i]]++] = i

                val median = FloatArray(k)
                val hsv = FloatArray(k * 3)
                var scratch = FloatArray(64)
                for (c in 0 until k) {
                    val m = start[c + 1] - start[c]
                    if (m > scratch.size) scratch = FloatArray(m * 2)
                    var sr = 0; var sg = 0; var sb = 0; var surface = 0
                    var ar = 0; var ag = 0; var ab = 0
                    for (j in 0 until m) {
                        val i = order[start[c] + j]
                        scratch[j] = relief[i]
                        val r = rgb[i * 3].toInt() and 0xff; val g = rgb[i * 3 + 1].toInt() and 0xff; val b = rgb[i * 3 + 2].toInt() and 0xff
                        ar += r; ag += g; ab += b
                        if (relief[i] >= SURFACE_RELIEF) { sr += r; sg += g; sb += b; surface++ }
                    }
                    java.util.Arrays.sort(scratch, 0, m)
                    median[c] = scratch[m / 2]
                    val col = if (surface >= MIN_SURFACE_VOXELS) ColorUtil.rgbToHsv(sr / surface, sg / surface, sb / surface)
                    else ColorUtil.rgbToHsv(ar / m, ag / m, ab / m)
                    hsv[c * 3] = col[0]; hsv[c * 3 + 1] = col[1]; hsv[c * 3 + 2] = col[2]
                }
                val grid = Grid(k, cu, cv, index, median, FloatArray(k), hsv, start, order)
                for (c in 0 until k) {
                    var sum = median[c]; var count = 1
                    for (dx in -1..1) for (dy in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nb = grid.neighbour(c, dx, dy)
                        if (nb >= 0) { sum += median[nb]; count++ }
                    }
                    grid.relief[c] = sum / count
                }
                return grid
            }

            private const val SURFACE_RELIEF = 0.01f
            private const val MIN_SURFACE_VOXELS = 3
        }
    }

    /**
     * Colour of a hold from its voxels, without the ones that match the [wall] colour (unless that would
     * leave fewer than a quarter of them: a hold the colour of the wall keeps its own colour).
     */
    private fun holdColor(rgb: ByteArray, members: IntArray, m: Int, wall: WallColor?): IntArray {
        if (wall == null) return saturatedColor(rgb, members, m)
        val kept = IntArray(m)
        var k = 0
        for (j in 0 until m) {
            val i = members[j]
            if (!wall.matches(rgb[i * 3].toInt() and 0xff, rgb[i * 3 + 1].toInt() and 0xff, rgb[i * 3 + 2].toInt() and 0xff)) kept[k++] = i
        }
        return if (k >= max(Tuning.MIN_HOLD_VOXELS, m / 4)) saturatedColor(rgb, kept, k) else saturatedColor(rgb, members, m)
    }

    /**
     * Mean colour of the more saturated half of a hold's voxels. The plain mean mixes in shadowed sides and
     * wall colour bled in at the edges, which pulls a coloured hold toward grey under gym lighting; a grey
     * hold stays grey under either estimator.
     */
    private fun saturatedColor(rgb: ByteArray, members: IntArray, m: Int): IntArray {
        val sat = FloatArray(m) { k ->
            val i = members[k]
            val r = rgb[i * 3].toInt() and 0xff; val g = rgb[i * 3 + 1].toInt() and 0xff; val b = rgb[i * 3 + 2].toInt() and 0xff
            val hi = maxOf(r, g, b)
            if (hi == 0) 0f else (hi - minOf(r, g, b)).toFloat() / hi
        }
        val sorted = sat.copyOf(); sorted.sort()
        val median = sorted[m / 2]
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        for (k in 0 until m) {
            if (sat[k] < median) continue
            val i = members[k]
            sr += rgb[i * 3].toInt() and 0xff; sg += rgb[i * 3 + 1].toInt() and 0xff; sb += rgb[i * 3 + 2].toInt() and 0xff
            n++
        }
        return intArrayOf((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    /**
     * Replaces each plane distance in [relief] with its height above the local surface: the median plane
     * distance of each [Tuning.OUTDOOR_RELIEF_CELL] cell in (u, v), interpolated between cell centres.
     */
    private fun subtractLocalSurface(relief: FloatArray, u: FloatArray, v: FloatArray) {
        val n = relief.size
        val cell = Tuning.OUTDOOR_RELIEF_CELL
        val members = HashMap<Long, MutableList<Int>>()
        for (i in 0 until n) {
            members.getOrPut(VoxelKey.pack(floor(u[i] / cell).toInt(), floor(v[i] / cell).toInt(), 0)) { ArrayList() }.add(i)
        }
        val medians = HashMap<Long, Float>(members.size * 2)
        for ((key, list) in members) {
            val sorted = FloatArray(list.size) { relief[list[it]] }
            sorted.sort()
            medians[key] = sorted[sorted.size / 2]
        }
        for (i in 0 until n) {
            // Bilinear blend of the four nearest cell medians; missing cells drop out of the weighting.
            val fu = u[i] / cell - 0.5f; val fv = v[i] / cell - 0.5f
            val i0 = floor(fu).toInt(); val j0 = floor(fv).toInt()
            val tu = fu - i0; val tv = fv - j0
            var base = 0f; var weight = 0f
            for (di in 0..1) for (dj in 0..1) {
                val w = (if (di == 0) 1f - tu else tu) * (if (dj == 0) 1f - tv else tv)
                val median = medians[VoxelKey.pack(i0 + di, j0 + dj, 0)] ?: continue
                base += w * median; weight += w
            }
            if (weight > 1e-6f) relief[i] -= base / weight
        }
    }
}
