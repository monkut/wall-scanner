package io.github.monkut.wallscanner.core

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
) {
    /** Primary route (first of [routeIds]); -1 when unassigned. */
    var routeId: Int = -1
    /** All routes this hold belongs to (tape and free walls share holds between routes). */
    val routeIds: MutableList<Int> = ArrayList()
    var role: HoldRole = HoldRole.ANY
    val hsv: FloatArray = ColorUtil.rgbToHsv(r, g, b)
    val hue: Float get() = hsv[0]
    val sat: Float get() = hsv[1]
    val value: Float get() = hsv[2]
}

/** Clusters voxels that protrude from the wall plane into holds (26-connected cells of [Tuning.CLUSTER_CELL]). */
object HoldDetector {
    /**
     * [localRelief] measures hold height against the local rock surface (per-cell median of the plane
     * distance, bilinearly interpolated) instead of the plane itself; used for outdoor rock, which is not flat.
     */
    fun detect(cloud: VoxelCloud, frame: WallFrame, localRelief: Boolean = false): List<Hold> {
        val n = cloud.count
        val xyz = cloud.xyz
        val relief = FloatArray(n) { frame.d(xyz[it * 3], xyz[it * 3 + 1], xyz[it * 3 + 2]) }
        if (localRelief) subtractLocalSurface(relief, cloud, frame)
        val candidates = IntArray(n)
        var m = 0
        for (i in 0 until n) {
            if (relief[i] >= Tuning.MIN_HOLD_HEIGHT && relief[i] <= Tuning.MAX_HOLD_HEIGHT) candidates[m++] = i
        }
        if (m == 0) return emptyList()

        val u = FloatArray(m); val v = FloatArray(m); val d = FloatArray(m)
        val cu = IntArray(m); val cv = IntArray(m); val cd = IntArray(m)
        val parent = IntArray(m) { it }
        val cellRep = HashMap<Long, Int>(m * 2)
        val inv = 1f / Tuning.CLUSTER_CELL

        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        for (k in 0 until m) {
            val i = candidates[k]
            val x = xyz[i * 3]; val y = xyz[i * 3 + 1]; val z = xyz[i * 3 + 2]
            u[k] = frame.u(x, y, z); v[k] = frame.v(x, y, z); d[k] = relief[i]
            cu[k] = floor(u[k] * inv).toInt(); cv[k] = floor(v[k] * inv).toInt(); cd[k] = floor(d[k] * inv).toInt()
            val key = VoxelKey.pack(cu[k], cv[k], cd[k])
            val rep = cellRep[key]
            if (rep == null) cellRep[key] = k else union(k, rep)
        }
        for (k in 0 until m) {
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val rep = cellRep[VoxelKey.pack(cu[k] + dx, cv[k] + dy, cd[k] + dz)] ?: continue
                union(k, rep)
            }
        }

        val groups = HashMap<Int, MutableList<Int>>()
        for (k in 0 until m) groups.getOrPut(find(k)) { ArrayList() }.add(k)

        val holds = ArrayList<Hold>()
        for (members in groups.values) {
            if (members.size < Tuning.MIN_HOLD_VOXELS) continue
            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            var maxD = 0f
            var sx = 0.0; var sy = 0.0; var sz = 0.0
            for (k in members) {
                val i = candidates[k]
                sx += xyz[i * 3]; sy += xyz[i * 3 + 1]; sz += xyz[i * 3 + 2]
                if (u[k] < minU) minU = u[k]; if (u[k] > maxU) maxU = u[k]
                if (v[k] < minV) minV = v[k]; if (v[k] > maxV) maxV = v[k]
                if (d[k] > maxD) maxD = d[k]
            }
            val width = maxU - minU
            val tall = maxV - minV
            val extent = max(width, tall)
            if (extent < Tuning.MIN_HOLD_EXTENT || extent > Tuning.MAX_HOLD_EXTENT) continue
            val count = members.size
            val cx = (sx / count).toFloat(); val cy = (sy / count).toFloat(); val cz = (sz / count).toFloat()
            val color = saturatedColor(cloud.rgb, candidates, members)
            holds.add(
                Hold(
                    id = holds.size + 1,
                    cx = cx, cy = cy, cz = cz,
                    u = frame.u(cx, cy, cz), v = frame.v(cx, cy, cz),
                    width = width, tall = tall, height = maxD,
                    r = color[0], g = color[1], b = color[2],
                    voxels = count,
                )
            )
        }
        holds.sortWith(compareBy({ it.v }, { it.u }))
        return holds.mapIndexed { index, h ->
            Hold(index + 1, h.cx, h.cy, h.cz, h.u, h.v, h.width, h.tall, h.height, h.r, h.g, h.b, h.voxels)
        }
    }

    /**
     * Mean colour of the more saturated half of a hold's voxels. The plain mean mixes in shadowed sides and
     * wall colour bled in at the edges, which pulls a coloured hold toward grey under gym lighting; a grey
     * hold stays grey under either estimator.
     */
    private fun saturatedColor(rgb: ByteArray, candidates: IntArray, members: List<Int>): IntArray {
        val sat = FloatArray(members.size) { k ->
            val i = candidates[members[k]]
            val r = rgb[i * 3].toInt() and 0xff; val g = rgb[i * 3 + 1].toInt() and 0xff; val b = rgb[i * 3 + 2].toInt() and 0xff
            val hi = maxOf(r, g, b)
            if (hi == 0) 0f else (hi - minOf(r, g, b)).toFloat() / hi
        }
        val sorted = sat.copyOf(); sorted.sort()
        val median = sorted[sorted.size / 2]
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        for (k in members.indices) {
            if (sat[k] < median) continue
            val i = candidates[members[k]]
            sr += rgb[i * 3].toInt() and 0xff; sg += rgb[i * 3 + 1].toInt() and 0xff; sb += rgb[i * 3 + 2].toInt() and 0xff
            n++
        }
        return intArrayOf((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    /**
     * Replaces each plane distance in [relief] with its height above the local surface: the median plane
     * distance of each [Tuning.OUTDOOR_RELIEF_CELL] cell in (u, v), interpolated between cell centres.
     */
    private fun subtractLocalSurface(relief: FloatArray, cloud: VoxelCloud, frame: WallFrame) {
        val n = cloud.count
        val xyz = cloud.xyz
        val cell = Tuning.OUTDOOR_RELIEF_CELL
        val u = FloatArray(n); val v = FloatArray(n)
        val members = HashMap<Long, MutableList<Int>>()
        for (i in 0 until n) {
            u[i] = frame.u(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])
            v[i] = frame.v(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])
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
