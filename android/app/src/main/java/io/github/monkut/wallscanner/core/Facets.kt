package io.github.monkut.wallscanner.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Splits a wall into planar faces (#8 phase 2): the top of a wall that kicks back into a slab or over into a
 * roof, a side wall turning a corner. The points near the fitted plane are binned into [CELL] cells of its
 * (u, v); each cell gets a local normal from the points of its 3 × 3 neighbourhood; cells grow into regions
 * while their normals stay within [JOIN_DEG] of the region's seed; regions of at least [MIN_FACE_AREA] whose
 * plane turns at least [MIN_FACE_ANGLE] from the largest one are faces of their own. Walls that are one plane
 * (within those thresholds) come back as the single frame they went in with, so nothing changes for them.
 */
object Facets {
    const val CELL = 0.10f
    /** Points this far from the fitted plane take part; further is mats, people and the next room. */
    const val BAND = 0.5f
    const val MIN_CELL_POINTS = 40
    /** A cell's normal comes from the neighbourhood points within this of the cell's median distance: the surface, not the holds on it. */
    const val NORMAL_BAND = 0.08f
    const val JOIN_DEG = 10f
    const val MIN_FACE_AREA = 1.0f
    const val MIN_FACE_ANGLE = 10f
    const val MAX_FACES = 3

    /** [areas] of the faces kept; [regions] every region over 0.2 m² as (area, degrees from the fitted plane), for logs. */
    class Found(val surface: WallSurface, val areas: List<Float>, val regions: List<Pair<Float, Float>> = emptyList()) {
        val summary: String get() = regions.joinToString(prefix = "[", postfix = "]") { "%.1f m² @ %.1f°".format(java.util.Locale.US, it.first, it.second) }
    }

    fun find(cloud: VoxelCloud, frame: WallFrame): Found {
        val n = cloud.count
        val xyz = cloud.xyz
        // 1. Bin the near-plane points into cells.
        val u = FloatArray(n); val v = FloatArray(n); val d = FloatArray(n)
        var minU = Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        val near = BooleanArray(n)
        for (i in 0 until n) {
            val x = xyz[i * 3]; val y = xyz[i * 3 + 1]; val z = xyz[i * 3 + 2]
            d[i] = frame.d(x, y, z)
            if (abs(d[i]) > BAND) continue
            near[i] = true
            u[i] = frame.u(x, y, z); v[i] = frame.v(x, y, z)
            if (u[i] < minU) minU = u[i]; if (u[i] > maxU) maxU = u[i]; if (v[i] < minV) minV = v[i]; if (v[i] > maxV) maxV = v[i]
        }
        if (minU >= maxU || minV >= maxV) return Found(WallSurface.single(frame), emptyList())
        val w = (floor((maxU - minU) / CELL)).toInt() + 1; val h = (floor((maxV - minV) / CELL)).toInt() + 1
        val cellOf = IntArray(n) { -1 }
        val count = IntArray(w * h)
        for (i in 0 until n) {
            if (!near[i]) continue
            val c = ((v[i] - minV) / CELL).toInt() * w + ((u[i] - minU) / CELL).toInt()
            cellOf[i] = c; count[c]++
        }
        val start = IntArray(w * h + 1)
        for (c in 0 until w * h) start[c + 1] = start[c] + count[c]
        val cursor = start.copyOf(w * h)
        val order = IntArray(start[w * h])
        for (i in 0 until n) { val c = cellOf[i]; if (c >= 0) order[cursor[c]++] = i }
        // 2. Per cell: median distance and a local normal.
        val median = FloatArray(w * h) { Float.NaN }
        val normals = FloatArray(w * h * 3)
        val valid = BooleanArray(w * h)
        var scratch = FloatArray(256)
        for (c in 0 until w * h) {
            val m = count[c]
            if (m < MIN_CELL_POINTS) continue
            if (m > scratch.size) scratch = FloatArray(m * 2)
            for (j in 0 until m) scratch[j] = d[order[start[c] + j]]
            java.util.Arrays.sort(scratch, 0, m)
            median[c] = scratch[m / 2]
        }
        val cov = DoubleArray(6)
        for (c in 0 until w * h) {
            if (median[c].isNaN()) continue
            val cx = c % w; val cy = c / w
            var k = 0; var sx = 0.0; var sy = 0.0; var sz = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val nc = ny * w + nx
                for (j in start[nc] until start[nc + 1]) {
                    val i = order[j]
                    if (abs(d[i] - median[c]) > NORMAL_BAND) continue
                    sx += xyz[i * 3]; sy += xyz[i * 3 + 1]; sz += xyz[i * 3 + 2]; k++
                }
            }
            if (k < MIN_CELL_POINTS) continue
            val mx = sx / k; val my = sy / k; val mz = sz / k
            java.util.Arrays.fill(cov, 0.0)
            for (dy in -1..1) for (dx in -1..1) {
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val nc = ny * w + nx
                for (j in start[nc] until start[nc + 1]) {
                    val i = order[j]
                    if (abs(d[i] - median[c]) > NORMAL_BAND) continue
                    val px = xyz[i * 3] - mx; val py = xyz[i * 3 + 1] - my; val pz = xyz[i * 3 + 2] - mz
                    cov[0] += px * px; cov[1] += px * py; cov[2] += px * pz; cov[3] += py * py; cov[4] += py * pz; cov[5] += pz * pz
                }
            }
            val nrm = smallestEigenvector(cov, frame.plane.nx.toDouble(), frame.plane.ny.toDouble(), frame.plane.nz.toDouble())
            normals[c * 3] = nrm[0].toFloat(); normals[c * 3 + 1] = nrm[1].toFloat(); normals[c * 3 + 2] = nrm[2].toFloat()
            valid[c] = true
        }
        // 3. Regions of like normals: a cell joins while it is within JOIN_DEG of the region's mean normal so far, then
        //    neighbouring regions whose mean normals agree merge, so the result does not hang on where the growth started.
        val label = IntArray(w * h) { -1 }
        val grown = ArrayList<IntArray>()
        val queue = IntArray(w * h)
        val cosJoin = kotlin.math.cos(Math.toRadians(JOIN_DEG.toDouble())).toFloat()
        for (seed in 0 until w * h) {
            if (!valid[seed] || label[seed] >= 0) continue
            val id = grown.size
            var sx = normals[seed * 3]; var sy = normals[seed * 3 + 1]; var sz = normals[seed * 3 + 2]
            var head = 0; var tail = 0
            queue[tail++] = seed; label[seed] = id
            while (head < tail) {
                val c = queue[head++]
                val cx = c % w; val cy = c / w
                val len = sqrt(sx * sx + sy * sy + sz * sz)
                val mx = sx / len; val my = sy / len; val mz = sz / len
                for ((dx, dy) in NEIGHBOURS) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val nc = ny * w + nx
                    if (!valid[nc] || label[nc] >= 0) continue
                    val dot = normals[nc * 3] * mx + normals[nc * 3 + 1] * my + normals[nc * 3 + 2] * mz
                    if (dot < cosJoin) continue
                    label[nc] = id; queue[tail++] = nc
                    sx += normals[nc * 3]; sy += normals[nc * 3 + 1]; sz += normals[nc * 3 + 2]
                }
            }
            grown.add(queue.copyOf(tail))
        }
        val regions = mergeAlike(grown, label, normals, w, h, cosJoin)
        // 4. Faces: the largest region is the primary; others big enough and turned enough join.
        val report = regions.filter { it.size * CELL * CELL >= 0.2f }.sortedByDescending { it.size }.take(6).map { r ->
            var nx = 0f; var ny = 0f; var nz = 0f
            for (c in r) { nx += normals[c * 3]; ny += normals[c * 3 + 1]; nz += normals[c * 3 + 2] }
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            r.size * CELL * CELL to degrees(nx / len, ny / len, nz / len, frame.plane.nx, frame.plane.ny, frame.plane.nz)
        }
        val big = regions.filter { it.size * CELL * CELL >= MIN_FACE_AREA }.sortedByDescending { it.size }
        if (big.size < 2) return Found(WallSurface.single(frame), big.map { it.size * CELL * CELL }, report)
        // The primary face keeps the fitted plane it came in with, so (u, v) on it stay what every single-plane scene,
        // reference and confirmed route already use; only the other faces unfold about their seams with it.
        val primaryPlane = frame.plane
        val primaryFace = Face(primaryPlane)
        val secondaries = ArrayList<Plane>()
        val areas = ArrayList<Float>()
        areas.add(big[0].size * CELL * CELL)
        for (region in big.drop(1)) {
            if (secondaries.size >= MAX_FACES - 1) break
            val plane = fitPlane(region, order, start, median, d, xyz, frame) ?: continue
            if (Face(plane).angleTo(primaryFace) < MIN_FACE_ANGLE) continue
            secondaries.add(plane)
            areas.add(region.size * CELL * CELL)
        }
        if (secondaries.isEmpty()) return Found(WallSurface.single(frame), areas, report)
        return Found(WallSurface.unfold(primaryPlane, secondaries), areas, report)
    }

    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** Joins touching regions whose mean normals are within the join angle, largest pairs first, until none are. */
    private fun mergeAlike(grown: List<IntArray>, label: IntArray, normals: FloatArray, w: Int, h: Int, cosJoin: Float): List<IntArray> {
        val parent = IntArray(grown.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        val sums = Array(grown.size) { r -> FloatArray(3).also { s -> for (c in grown[r]) { s[0] += normals[c * 3]; s[1] += normals[c * 3 + 1]; s[2] += normals[c * 3 + 2] } } }
        val sizes = IntArray(grown.size) { grown[it].size }
        // Touching pairs.
        val touching = HashSet<Long>()
        for (c in label.indices) {
            val a = label[c]
            if (a < 0) continue
            val cx = c % w; val cy = c / w
            if (cx + 1 < w) { val b = label[c + 1]; if (b >= 0 && b != a) touching.add(minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()) }
            if (cy + 1 < h) { val b = label[c + w]; if (b >= 0 && b != a) touching.add(minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()) }
        }
        var merged = true
        while (merged) {
            merged = false
            for (key in touching.sortedByDescending { sizes[root((it shr 32).toInt())] + sizes[root((it and 0xffffffffL).toInt())] }) {
                val a = root((key shr 32).toInt()); val b = root((key and 0xffffffffL).toInt())
                if (a == b) continue
                val sa = sums[a]; val sb = sums[b]
                val la = sqrt(sa[0] * sa[0] + sa[1] * sa[1] + sa[2] * sa[2]); val lb = sqrt(sb[0] * sb[0] + sb[1] * sb[1] + sb[2] * sb[2])
                if (la < 1e-6f || lb < 1e-6f) continue
                val dot = (sa[0] * sb[0] + sa[1] * sb[1] + sa[2] * sb[2]) / (la * lb)
                if (dot < cosJoin) continue
                val (keep, drop) = if (sizes[a] >= sizes[b]) a to b else b to a
                parent[drop] = keep
                sums[keep][0] += sums[drop][0]; sums[keep][1] += sums[drop][1]; sums[keep][2] += sums[drop][2]
                sizes[keep] += sizes[drop]
                merged = true
            }
        }
        val out = HashMap<Int, ArrayList<Int>>()
        for ((i, cells) in grown.withIndex()) out.getOrPut(root(i)) { ArrayList() }.addAll(cells.asList())
        return out.values.map { it.toIntArray() }
    }

    /** Least-squares plane through the surface points of [cells], its normal on the same side as [frame]'s. */
    private fun fitPlane(cells: IntArray, order: IntArray, start: IntArray, median: FloatArray, d: FloatArray, xyz: FloatArray, frame: WallFrame): Plane? {
        var k = 0; var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (c in cells) for (j in start[c] until start[c + 1]) {
            val i = order[j]
            if (abs(d[i] - median[c]) > NORMAL_BAND) continue
            sx += xyz[i * 3]; sy += xyz[i * 3 + 1]; sz += xyz[i * 3 + 2]; k++
        }
        if (k < 100) return null
        val mx = sx / k; val my = sy / k; val mz = sz / k
        val cov = DoubleArray(6)
        for (c in cells) for (j in start[c] until start[c + 1]) {
            val i = order[j]
            if (abs(d[i] - median[c]) > NORMAL_BAND) continue
            val px = xyz[i * 3] - mx; val py = xyz[i * 3 + 1] - my; val pz = xyz[i * 3 + 2] - mz
            cov[0] += px * px; cov[1] += px * py; cov[2] += px * pz; cov[3] += py * py; cov[4] += py * pz; cov[5] += pz * pz
        }
        val nrm = smallestEigenvector(cov, frame.plane.nx.toDouble(), frame.plane.ny.toDouble(), frame.plane.nz.toDouble())
        return Plane(mx.toFloat(), my.toFloat(), mz.toFloat(), nrm[0].toFloat(), nrm[1].toFloat(), nrm[2].toFloat())
    }

    /**
     * Unit eigenvector of the smallest eigenvalue of the symmetric matrix [c] = (xx, xy, xz, yy, yz, zz): power
     * iteration on (trace · I − C) from ([sx], [sy], [sz]), oriented to point the seed's way.
     */
    private fun smallestEigenvector(c: DoubleArray, sx: Double, sy: Double, sz: Double): DoubleArray {
        val shift = c[0] + c[3] + c[5]
        val m00 = shift - c[0]; val m11 = shift - c[3]; val m22 = shift - c[5]
        val m01 = -c[1]; val m02 = -c[2]; val m12 = -c[4]
        var vx = sx; var vy = sy; var vz = sz
        repeat(60) {
            val nx = m00 * vx + m01 * vy + m02 * vz
            val ny = m01 * vx + m11 * vy + m12 * vz
            val nz = m02 * vx + m12 * vy + m22 * vz
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12) { vx = nx / len; vy = ny / len; vz = nz / len }
        }
        if (vx * sx + vy * sy + vz * sz < 0) { vx = -vx; vy = -vy; vz = -vz }
        return doubleArrayOf(vx, vy, vz)
    }

    /** Degrees between two unit normals. */
    fun degrees(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float =
        Math.toDegrees(acos((ax * bx + ay * by + az * bz).coerceIn(-1f, 1f).toDouble())).toFloat()
}
