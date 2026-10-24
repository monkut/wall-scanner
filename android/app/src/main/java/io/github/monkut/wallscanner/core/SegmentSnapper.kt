package io.github.monkut.wallscanner.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SnapResult(
    val transform: Rigid,
    val planeAngleDeg: Double,
    val planeOffset: Double,
    val shiftU: Double,
    val shiftV: Double,
    val rollDeg: Double,
    val overlapCells: Int,
    val score: Double,
    val accepted: Boolean,
) {
    override fun toString(): String =
        "${if (accepted) "accepted" else "rejected"} tilt=${"%.2f".format(planeAngleDeg)}° depth=${"%.3f".format(planeOffset)} " +
            "shift=(${"%.3f".format(shiftU)}, ${"%.3f".format(shiftV)}) roll=${"%.1f".format(rollDeg)}° " +
            "overlap=$overlapCells score=${"%.3f".format(score)}"
}

/**
 * Registers a segment of a wall scan onto the already-registered reference cloud of the same wall.
 *
 * Stage 1 fits a wall plane to both clouds and aligns the segment plane onto the reference plane (tilt + depth).
 * Stage 2 rasterises both clouds into relief maps (height above the wall per cell, holds show as bumps) and
 * searches the in-plane shift and roll that maximise their normalised cross-correlation over the overlap.
 * Returns null when either cloud has no wall plane; otherwise a result whose [SnapResult.accepted] says
 * whether the plane disagreement was plausible and the overlap and score passed the thresholds in [Tuning].
 * A rejected result carries the stage-1 transform for diagnostics only; callers must not apply it. [toward] is a point on the climber's side
 * of the wall (e.g. the mean camera position) used to orient both planes consistently.
 */
object SegmentSnapper {
    private class Relief(val w: Int, val h: Int, val u0: Double, val v0: Double, val cell: Double) {
        val value = DoubleArray(w * h)
        val filled = BooleanArray(w * h)

        fun put(u: Double, v: Double, d: Double) {
            val i = floor((u - u0) / cell).toInt()
            val j = floor((v - v0) / cell).toInt()
            if (i < 0 || j < 0 || i >= w || j >= h) return
            val k = j * w + i
            if (!filled[k] || d > value[k]) { value[k] = d; filled[k] = true }
        }
    }

    private class Match(val shiftCells: Int, val shiftRows: Int, val rollDeg: Double, val overlap: Int, val score: Double)

    fun snap(reference: VoxelCloud, moving: VoxelCloud, toward: FloatArray, random: Random = Random(42)): SnapResult? {
        if (reference.count < Tuning.SNAP_MIN_VOXELS || moving.count < Tuning.SNAP_MIN_VOXELS) return null
        val ref = PlaneFitter.fit(reference, random, toward[0], toward[1], toward[2])?.plane ?: return null
        val mov = PlaneFitter.fit(moving, random, toward[0], toward[1], toward[2])?.plane ?: return null

        // Stage 1: rotate the moving normal onto the reference normal about the moving plane origin,
        // then slide along the reference normal until the moving origin lies on the reference plane.
        val dot = (mov.nx * ref.nx + mov.ny * ref.ny + mov.nz * ref.nz).toDouble().coerceIn(-1.0, 1.0)
        val angle = acos(dot)
        val ax = (mov.ny * ref.nz - mov.nz * ref.ny).toDouble()
        val ay = (mov.nz * ref.nx - mov.nx * ref.nz).toDouble()
        val az = (mov.nx * ref.ny - mov.ny * ref.nx).toDouble()
        val tilt = Rigid.rotationAbout(ax, ay, az, angle, mov.ox.toDouble(), mov.oy.toDouble(), mov.oz.toDouble())
        val depth = ref.signedDistance(mov.ox, mov.oy, mov.oz).toDouble()
        val stage1 = Rigid.translation(-depth * ref.nx, -depth * ref.ny, -depth * ref.nz).compose(tilt)
        if (angle * 180 / PI > Tuning.SNAP_MAX_TILT_DEG || abs(depth) > Tuning.SNAP_MAX_DEPTH) {
            return rejected(stage1, angle, depth, 0, 0.0)
        }
        val moved = stage1.apply(moving)

        // Stage 2: relief maps in the reference wall frame.
        val frame = WallFrame(ref)
        val cell = Tuning.SNAP_CELL.toDouble()
        val refUvd = project(reference, frame)
        val movUvd = project(moved, frame)
        if (refUvd.size < 3 || movUvd.size < 3) return rejected(stage1, angle, depth, 0, 0.0)
        val bounds = bounds(refUvd, movUvd)
        val pad = Tuning.SNAP_MAX_SHIFT.toDouble() + 2 * cell
        val u0 = bounds[0] - pad; val v0 = bounds[2] - pad
        val w = ceil((bounds[1] + pad - u0) / cell).toInt() + 1
        val h = ceil((bounds[3] + pad - v0) / cell).toInt() + 1
        val refMap = Relief(w, h, u0, v0, cell)
        for (p in refUvd) refMap.put(p[0], p[1], p[2])

        val um = movUvd.sumOf { it[0] } / movUvd.size
        val vm = movUvd.sumOf { it[1] } / movUvd.size
        val maxShiftCells = (Tuning.SNAP_MAX_SHIFT / Tuning.SNAP_CELL).roundToInt()
        var best: Match? = null
        var roll = -Tuning.SNAP_MAX_ROLL_DEG.toDouble()
        while (roll <= Tuning.SNAP_MAX_ROLL_DEG + 1e-9) {
            val movMap = Relief(w, h, u0, v0, cell)
            val c = cos(roll * PI / 180); val s = sin(roll * PI / 180)
            for (p in movUvd) {
                val du = p[0] - um; val dv = p[1] - vm
                movMap.put(um + c * du - s * dv, vm + s * du + c * dv, p[2])
            }
            val m = bestShift(refMap, movMap, maxShiftCells, roll)
            if (m != null && (best == null || m.score > best.score)) best = m
            roll += Tuning.SNAP_ROLL_STEP_DEG
        }
        val match = best ?: return rejected(stage1, angle, depth, 0, 0.0)

        val shiftU = match.shiftCells * cell
        val shiftV = match.shiftRows * cell
        val accepted = match.overlap >= Tuning.SNAP_MIN_OVERLAP_CELLS && match.score >= Tuning.SNAP_MIN_SCORE
        if (!accepted) return rejected(stage1, angle, depth, match.overlap, match.score)

        // Roll about the reference normal through the moving centroid, then shift within the plane.
        val cx = ref.ox + um * frame.rx + vm * frame.ux
        val cy = ref.oy + um * frame.ry + vm * frame.uy
        val cz = ref.oz + um * frame.rz + vm * frame.uz
        val rollT = Rigid.rotationAbout(ref.nx.toDouble(), ref.ny.toDouble(), ref.nz.toDouble(), match.rollDeg * PI / 180, cx, cy, cz)
        val shiftT = Rigid.translation(
            shiftU * frame.rx + shiftV * frame.ux, shiftU * frame.ry + shiftV * frame.uy, shiftU * frame.rz + shiftV * frame.uz,
        )
        val transform = shiftT.compose(rollT).compose(stage1)
        return SnapResult(transform, angle * 180 / PI, depth, shiftU, shiftV, match.rollDeg, match.overlap, match.score, true)
    }

    private fun rejected(stage1: Rigid, angle: Double, depth: Double, overlap: Int, score: Double) =
        SnapResult(stage1, angle * 180 / PI, depth, 0.0, 0.0, 0.0, overlap, score, false)

    /** (u, v, relief) for every point within the relief band of the wall plane. */
    private fun project(cloud: VoxelCloud, frame: WallFrame): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(cloud.count)
        for (i in 0 until cloud.count) {
            val x = cloud.xyz[i * 3]; val y = cloud.xyz[i * 3 + 1]; val z = cloud.xyz[i * 3 + 2]
            val d = frame.d(x, y, z)
            if (d < Tuning.SNAP_MIN_RELIEF || d > Tuning.SNAP_MAX_RELIEF) continue
            out.add(doubleArrayOf(frame.u(x, y, z).toDouble(), frame.v(x, y, z).toDouble(), d.toDouble()))
        }
        return out
    }

    private fun bounds(a: List<DoubleArray>, b: List<DoubleArray>): DoubleArray {
        var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE; var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
        for (list in listOf(a, b)) for (p in list) {
            minU = min(minU, p[0]); maxU = max(maxU, p[0]); minV = min(minV, p[1]); maxV = max(maxV, p[1])
        }
        return doubleArrayOf(minU, maxU, minV, maxV)
    }

    /** Normalised cross-correlation of [mov] against [ref] for every integer shift within ±[maxShift] cells. */
    private fun bestShift(ref: Relief, mov: Relief, maxShift: Int, rollDeg: Double): Match? {
        val w = ref.w; val h = ref.h
        val movCells = ArrayList<Int>()
        for (k in 0 until w * h) if (mov.filled[k]) movCells.add(k)
        if (movCells.isEmpty()) return null
        val cells = movCells.toIntArray()
        var best: Match? = null
        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                var n = 0
                var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
                for (k in cells) {
                    val i = k % w + dx
                    val j = k / w + dy
                    if (i < 0 || j < 0 || i >= w || j >= h) continue
                    val rk = j * w + i
                    if (!ref.filled[rk]) continue
                    val a = mov.value[k]; val b = ref.value[rk]
                    n++; sx += a; sy += b; sxx += a * a; syy += b * b; sxy += a * b
                }
                if (n < Tuning.SNAP_MIN_OVERLAP_CELLS) continue
                val varA = sxx - sx * sx / n
                val varB = syy - sy * sy / n
                if (varA <= 1e-12 || varB <= 1e-12) continue
                val score = (sxy - sx * sy / n) / sqrt(varA * varB)
                if (best == null || score > best.score) best = Match(dx, dy, rollDeg, n, score)
            }
        }
        return best
    }
}
