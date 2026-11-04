package io.github.monkut.wallscanner.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** A hold as registration sees it: world centre, HSV colour and the larger of its sides. */
class HoldPoint(val x: Float, val y: Float, val z: Float, val hue: Float, val sat: Float, val value: Float, val size: Float)

/** How a moving capture sits on a reference one: [transform] takes moving-world points into the reference world. */
class Registration(
    val transform: Rigid,
    val yawDeg: Double,
    /** Moving holds that land on a reference hold, and moving holds that land among the reference holds at all. */
    val holdMatches: Int,
    val holdsInside: Int,
    /** Horizontal span of the matched holds: a span under [CaptureRegistration.THIN_OVERLAP] fixes the yaw poorly. */
    val overlapSpan: Double,
    val icpPairs: Int,
    val rmse: Double,
    val accepted: Boolean,
) {
    val thin: Boolean get() = overlapSpan < CaptureRegistration.THIN_OVERLAP

    override fun toString(): String =
        "${if (accepted) "accepted" else "rejected"} yaw=${"%.1f".format(yawDeg)}° shift=(${"%.2f".format(transform.t[0])}, ${"%.2f".format(transform.t[1])}, ${"%.2f".format(transform.t[2])}) " +
            "holds=$holdMatches/$holdsInside span=${"%.2f".format(overlapSpan)} m icpPairs=$icpPairs rmse=${"%.1f".format(rmse * 1000)} mm${if (thin) " (thin overlap)" else ""}"
}

/**
 * Registers one capture of a wall onto another of the same set (#7, #8 phase 3). Both ARCore worlds are gravity
 * aligned, so the unknown is a yaw about the vertical plus a translation. The holds are the signal: a wall is a
 * flat plane, and two scans of different parts of it overlap as well as two scans of the same part, so the clouds
 * alone cannot tell where one sits on the other (point ICP slides into such an overlay). Coarse: every hold pair
 * of like colour and size votes, per yaw step, for the shift that puts them together; the best-supported votes
 * are judged by the share of moving holds among the reference holds that land on one, and the winner is refined
 * by least squares over its matches. Fine: a short point ICP on the clouds, kept within [ICP_MAX_MOVE] and
 * [ICP_MAX_TURN_DEG] of the hold solution (further is the overlay, not a correction). Accepted when at least
 * [MIN_HOLD_MATCHES] holds match, they are at least [MIN_MATCH_RATIO] of the moving holds in the overlap, and they
 * span at least [THIN_OVERLAP] across: the two Oct 29 noborock scans share 0.4 m of cut-off holds and are refused.
 */
object CaptureRegistration {
    const val COARSE_MATCH_DIST = 0.15f // holds cut by a scan edge sit well off their counterpart's centre
    const val THIN_OVERLAP = 0.8 // metres of horizontal span the matched holds should cover for a trustworthy yaw
    const val ICP_MAX_MOVE = 0.08
    const val ICP_MAX_TURN_DEG = 2.0
    const val YAW_STEP_DEG = 1.5
    const val VOTE_BIN = 0.12 // metres; the translation bin of the yaw × shift vote
    const val TOP_VOTES = 200
    const val MIN_HOLD_MATCHES = 4
    const val HUE_TOL = 25f
    const val ICP_CELL = 0.02f
    const val ICP_ITERATIONS = 12
    const val ICP_MAX_PAIR = 0.04f // a correction of the hold solution, not a search
    const val ICP_FINAL_PAIR = 0.03f
    const val MIN_ICP_PAIRS = 500 // 500 cells of 2 cm = 0.2 m² of overlapping surface
    const val MAX_RMSE = 0.02
    const val FINAL_MATCH_DIST = 0.15f
    /** A moving hold this close to any reference hold is in the reference's hold-bearing area, so it ought to match one. */
    const val INSIDE_DIST = 0.5f
    /** Of the moving holds in that area, this fraction must land on a reference hold: two flat walls agree anywhere, holds do not. */
    const val MIN_MATCH_RATIO = 0.5

    fun register(refCloud: VoxelCloud, refHolds: List<HoldPoint>, movCloud: VoxelCloud, movHolds: List<HoldPoint>): Registration? {
        val coarse = coarse(refHolds, movHolds) ?: return null
        return icp(Grid(refCloud), Grid(movCloud), coarse, refHolds, movHolds)
    }

    /** Moving holds landing on a reference hold under [t] (one-to-one within [maxDist]) and those landing within [INSIDE_DIST] of any reference hold. */
    private fun holdScore(ref: List<HoldPoint>, mov: List<HoldPoint>, cands: List<IntArray>, t: Rigid, maxDist: Float): Pair<Int, Int> {
        val matches = matched(cands, ref, mov, t, maxDist).size
        val p = DoubleArray(3)
        var inside = 0
        for (b in mov) {
            t.apply(b.x.toDouble(), b.y.toDouble(), b.z.toDouble(), p)
            if (ref.any { a -> (a.x - p[0]) * (a.x - p[0]) + (a.y - p[1]) * (a.y - p[1]) + (a.z - p[2]) * (a.z - p[2]) < INSIDE_DIST * INSIDE_DIST }) inside++
        }
        return matches to inside
    }

    /** Horizontal span (metres) of the reference holds in [matches]: how much baseline fixes the yaw. */
    private fun span(ref: List<HoldPoint>, matches: List<IntArray>): Double {
        var best = 0.0
        for (i in matches.indices) for (j in i + 1 until matches.size) {
            val a = ref[matches[i][0]]; val b = ref[matches[j][0]]
            val d = Math.hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble())
            if (d > best) best = d
        }
        return best
    }

    /**
     * Yaw and translation from the holds, or null without [MIN_HOLD_MATCHES] agreeing holds. Every compatible hold
     * pair votes, for each yaw step, for the translation that would put them together (a Hough vote in yaw × shift);
     * the best-supported votes are checked by counting holds that land on each other and refined by least squares.
     * Holds cut by a scan's edge sit tens of centimetres off their counterpart, so the match distance is loose here;
     * the ICP that follows does the precision work.
     */
    fun coarse(refHolds: List<HoldPoint>, movHolds: List<HoldPoint>): Rigid? {
        val cands = ArrayList<IntArray>()
        for ((ia, a) in refHolds.withIndex()) for ((ib, b) in movHolds.withIndex()) if (compatible(a, b)) cands.add(intArrayOf(ia, ib))
        if (cands.size < MIN_HOLD_MATCHES) return null
        val steps = (360.0 / YAW_STEP_DEG).toInt()
        val votes = HashMap<Long, Int>()
        val offset = 1 shl 17
        fun key(k: Int, bx: Int, by: Int, bz: Int): Long = (k.toLong() shl 54) or ((bx + offset).toLong() shl 36) or ((by + offset).toLong() shl 18) or (bz + offset).toLong()
        for (k in 0 until steps) {
            val yaw = Math.toRadians(k * YAW_STEP_DEG)
            val c = cos(yaw); val s = sin(yaw)
            for ((ia, ib) in cands) {
                val a = refHolds[ia]; val b = movHolds[ib]
                val tx = a.x - (c * b.x - s * b.z); val ty = a.y - b.y; val tz = a.z - (s * b.x + c * b.z)
                // Vote for the bin and its neighbours, so a match straddling a bin edge still counts once.
                val bx = floor(tx / VOTE_BIN).toInt(); val by = floor(ty / VOTE_BIN).toInt(); val bz = floor(tz / VOTE_BIN).toInt()
                for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
                    val kk = key(k, bx + dx, by + dy, bz + dz)
                    votes[kk] = (votes[kk] ?: 0) + 1
                }
            }
        }
        val top = votes.entries.sortedByDescending { it.value }.take(TOP_VOTES)
        var bestT: Rigid? = null; var bestRatio = 0.0; var bestCount = 0
        for (entry in top) {
            val k = (entry.key shr 54).toInt()
            val bx = ((entry.key shr 36) and 0x3ffff).toInt() - offset; val by = ((entry.key shr 18) and 0x3ffff).toInt() - offset; val bz = (entry.key and 0x3ffff).toInt() - offset
            // The bin's lower corner was shared by the eight votes; the match sits half a bin below each.
            val t = yawRigid(Math.toRadians(k * YAW_STEP_DEG), (bx - 0.5) * VOTE_BIN, (by - 0.5) * VOTE_BIN, (bz - 0.5) * VOTE_BIN)
            val matches = matched(cands, refHolds, movHolds, t, COARSE_MATCH_DIST)
            if (matches.size < MIN_HOLD_MATCHES) continue
            val refined = solve(
                matches.map { (ia, _) -> doubleArrayOf(refHolds[ia].x.toDouble(), refHolds[ia].y.toDouble(), refHolds[ia].z.toDouble()) },
                matches.map { (_, ib) -> doubleArrayOf(movHolds[ib].x.toDouble(), movHolds[ib].y.toDouble(), movHolds[ib].z.toDouble()) },
            ) ?: continue
            val (again, inside) = holdScore(refHolds, movHolds, cands, refined, COARSE_MATCH_DIST)
            if (again < MIN_HOLD_MATCHES || inside < MIN_HOLD_MATCHES) continue
            val ratio = again.toDouble() / inside
            if (ratio > bestRatio || (ratio == bestRatio && again > bestCount)) { bestRatio = ratio; bestCount = again; bestT = refined }
        }
        return bestT
    }

    private fun compatible(a: HoldPoint, b: HoldPoint): Boolean {
        val ratio = if (b.size > 0f) a.size / b.size else 1f
        if (ratio < 0.5f || ratio > 2f) return false
        val coloured = a.sat >= Tuning.MIN_ROUTE_SATURATION && b.sat >= Tuning.MIN_ROUTE_SATURATION
        if (coloured) return ColorUtil.hueDistance(a.hue, b.hue) <= HUE_TOL
        if (a.sat >= Tuning.MIN_ROUTE_SATURATION || b.sat >= Tuning.MIN_ROUTE_SATURATION) return false
        return abs(a.value - b.value) <= 0.25f
    }

    /** Candidate holds landing within [maxDist] of each other under [t], one-to-one by distance: (ref index, mov index, mm). */
    private fun matched(cands: List<IntArray>, ref: List<HoldPoint>, mov: List<HoldPoint>, t: Rigid, maxDist: Float): List<IntArray> {
        val p = DoubleArray(3)
        val scored = ArrayList<IntArray>()
        for ((ia, ib) in cands) {
            val b = mov[ib]
            t.apply(b.x.toDouble(), b.y.toDouble(), b.z.toDouble(), p)
            val a = ref[ia]
            val d = sqrt((a.x - p[0]) * (a.x - p[0]) + (a.y - p[1]) * (a.y - p[1]) + (a.z - p[2]) * (a.z - p[2]))
            if (d < maxDist) scored.add(intArrayOf(ia, ib, (d * 1000).toInt()))
        }
        scored.sortBy { it[2] }
        val usedA = HashSet<Int>(); val usedB = HashSet<Int>()
        return scored.filter { (ia, ib, _) -> usedA.add(ia) && usedB.add(ib) }
    }

    /** Rotation by [yaw] about the vertical (x, z plane) then translation. */
    fun yawRigid(yaw: Double, tx: Double, ty: Double, tz: Double): Rigid {
        val c = cos(yaw); val s = sin(yaw)
        return Rigid(doubleArrayOf(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c), doubleArrayOf(tx, ty, tz))
    }

    /** Least-squares yaw + translation taking [mov] points onto [ref] points (same order). */
    fun solve(ref: List<DoubleArray>, mov: List<DoubleArray>): Rigid? {
        val n = ref.size
        if (n < 2) return null
        var ax = 0.0; var ay = 0.0; var az = 0.0; var bx = 0.0; var by = 0.0; var bz = 0.0
        for (i in 0 until n) { ax += ref[i][0]; ay += ref[i][1]; az += ref[i][2]; bx += mov[i][0]; by += mov[i][1]; bz += mov[i][2] }
        ax /= n; ay /= n; az /= n; bx /= n; by /= n; bz /= n
        var cross = 0.0; var dot = 0.0
        for (i in 0 until n) {
            val px = mov[i][0] - bx; val pz = mov[i][2] - bz; val qx = ref[i][0] - ax; val qz = ref[i][2] - az
            cross += px * qz - pz * qx; dot += px * qx + pz * qz
        }
        val yaw = atan2(cross, dot)
        val c = cos(yaw); val s = sin(yaw)
        val rbx = c * bx - s * bz; val rbz = s * bx + c * bz
        return yawRigid(yaw, ax - rbx, ay - by, az - rbz)
    }

    /** One point per [ICP_CELL] cell. */
    private class Grid(cloud: VoxelCloud) {
        val xs: FloatArray; val ys: FloatArray; val zs: FloatArray
        val index = HashMap<Long, Int>()
        init {
            val n = cloud.count
            val tx = FloatArray(n); val ty = FloatArray(n); val tz = FloatArray(n)
            var k = 0
            for (i in 0 until n) {
                val x = cloud.xyz[i * 3]; val y = cloud.xyz[i * 3 + 1]; val z = cloud.xyz[i * 3 + 2]
                val key = VoxelKey.pack(floor(x / ICP_CELL).toInt(), floor(y / ICP_CELL).toInt(), floor(z / ICP_CELL).toInt())
                if (index.containsKey(key)) continue
                index[key] = k; tx[k] = x; ty[k] = y; tz[k] = z; k++
            }
            xs = tx.copyOf(k); ys = ty.copyOf(k); zs = tz.copyOf(k)
        }
        val count: Int get() = xs.size

        /** Index of the nearest kept point within [maxDist] of (x, y, z), or -1. */
        fun nearest(x: Double, y: Double, z: Double, maxDist: Double): Int {
            val cx = floor(x / ICP_CELL).toInt(); val cy = floor(y / ICP_CELL).toInt(); val cz = floor(z / ICP_CELL).toInt()
            val reach = (maxDist / ICP_CELL).toInt() + 1
            var best = -1; var bestD = maxDist * maxDist
            for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
                val j = index[VoxelKey.pack(cx + dx, cy + dy, cz + dz)] ?: continue
                val ex = xs[j] - x; val ey = ys[j] - y; val ez = zs[j] - z
                val d = ex * ex + ey * ey + ez * ez
                if (d < bestD) { bestD = d; best = j }
            }
            return best
        }
    }

    /** Refines [start] (reference-from-moving) by a short, bounded ICP and judges the result by the holds. */
    private fun icp(ref: Grid, mov: Grid, start: Rigid, refHolds: List<HoldPoint>, movHolds: List<HoldPoint>): Registration {
        var t = start
        val p = DoubleArray(3)
        val refPts = ArrayList<DoubleArray>(); val movPts = ArrayList<DoubleArray>()
        var pairs = 0; var rmse = Double.MAX_VALUE
        for (iter in 0 until ICP_ITERATIONS) {
            val maxPair = if (iter < 5) ICP_MAX_PAIR.toDouble() else ICP_FINAL_PAIR.toDouble()
            refPts.clear(); movPts.clear()
            var sum = 0.0
            for (i in 0 until mov.count) {
                t.apply(mov.xs[i].toDouble(), mov.ys[i].toDouble(), mov.zs[i].toDouble(), p)
                val j = ref.nearest(p[0], p[1], p[2], maxPair)
                if (j < 0) continue
                val ex = ref.xs[j] - p[0]; val ey = ref.ys[j] - p[1]; val ez = ref.zs[j] - p[2]
                sum += ex * ex + ey * ey + ez * ez
                refPts.add(doubleArrayOf(ref.xs[j].toDouble(), ref.ys[j].toDouble(), ref.zs[j].toDouble()))
                movPts.add(doubleArrayOf(mov.xs[i].toDouble(), mov.ys[i].toDouble(), mov.zs[i].toDouble()))
            }
            pairs = refPts.size
            if (pairs < MIN_HOLD_MATCHES) break
            rmse = sqrt(sum / pairs)
            val next = solve(refPts, movPts) ?: break
            val moved = sqrt((next.t[0] - t.t[0]) * (next.t[0] - t.t[0]) + (next.t[1] - t.t[1]) * (next.t[1] - t.t[1]) + (next.t[2] - t.t[2]) * (next.t[2] - t.t[2]))
            val turned = abs(atan2(next.r[6], next.r[0]) - atan2(t.r[6], t.r[0]))
            t = next
            if (iter >= 3 && moved < 0.0005 && turned < 1e-4) break
        }
        // ICP that wandered off the hold solution found the overlay of two flat walls, not a better fit: keep the holds' answer.
        val moved = sqrt((t.t[0] - start.t[0]) * (t.t[0] - start.t[0]) + (t.t[1] - start.t[1]) * (t.t[1] - start.t[1]) + (t.t[2] - start.t[2]) * (t.t[2] - start.t[2]))
        val turned = Math.toDegrees(abs(atan2(t.r[6], t.r[0]) - atan2(start.r[6], start.r[0])))
        if (moved > ICP_MAX_MOVE || turned > ICP_MAX_TURN_DEG) { t = start; pairs = 0; rmse = Double.NaN }
        val yaw = Math.toDegrees(atan2(t.r[6], t.r[0]))
        val cands = ArrayList<IntArray>()
        for ((ia, a) in refHolds.withIndex()) for ((ib, b) in movHolds.withIndex()) if (compatible(a, b)) cands.add(intArrayOf(ia, ib))
        val finalMatches = matched(cands, refHolds, movHolds, t, FINAL_MATCH_DIST)
        val (matches, inside) = holdScore(refHolds, movHolds, cands, t, FINAL_MATCH_DIST)
        val overlapSpan = span(refHolds, finalMatches)
        // A thin overlap leaves the yaw to a handful of cut-off holds a few decimetres apart: the far end of the joined wall would swing by that error.
        val accepted = matches >= MIN_HOLD_MATCHES && matches >= MIN_MATCH_RATIO * inside && overlapSpan >= THIN_OVERLAP
        return Registration(t, yaw, matches, inside, overlapSpan, pairs, rmse, accepted)
    }
}
