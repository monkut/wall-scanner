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
    var routeId: Int = -1
    val hsv: FloatArray = ColorUtil.rgbToHsv(r, g, b)
    val hue: Float get() = hsv[0]
    val sat: Float get() = hsv[1]
    val value: Float get() = hsv[2]
}

/** Clusters voxels that protrude from the wall plane into holds (26-connected cells of [Tuning.CLUSTER_CELL]). */
object HoldDetector {
    fun detect(cloud: VoxelCloud, frame: WallFrame): List<Hold> {
        val n = cloud.count
        val xyz = cloud.xyz
        val candidates = IntArray(n)
        var m = 0
        for (i in 0 until n) {
            val d = frame.d(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])
            if (d >= Tuning.MIN_HOLD_HEIGHT && d <= Tuning.MAX_HOLD_HEIGHT) candidates[m++] = i
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
            u[k] = frame.u(x, y, z); v[k] = frame.v(x, y, z); d[k] = frame.d(x, y, z)
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
            var sr = 0L; var sg = 0L; var sb = 0L
            for (k in members) {
                val i = candidates[k]
                sx += xyz[i * 3]; sy += xyz[i * 3 + 1]; sz += xyz[i * 3 + 2]
                sr += cloud.rgb[i * 3].toInt() and 0xff
                sg += cloud.rgb[i * 3 + 1].toInt() and 0xff
                sb += cloud.rgb[i * 3 + 2].toInt() and 0xff
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
            holds.add(
                Hold(
                    id = holds.size + 1,
                    cx = cx, cy = cy, cz = cz,
                    u = frame.u(cx, cy, cz), v = frame.v(cx, cy, cz),
                    width = width, tall = tall, height = maxD,
                    r = (sr / count).toInt(), g = (sg / count).toInt(), b = (sb / count).toInt(),
                    voxels = count,
                )
            )
        }
        holds.sortWith(compareBy({ it.v }, { it.u }))
        return holds.mapIndexed { index, h ->
            Hold(index + 1, h.cx, h.cy, h.cz, h.u, h.v, h.width, h.tall, h.height, h.r, h.g, h.b, h.voxels)
        }
    }
}
