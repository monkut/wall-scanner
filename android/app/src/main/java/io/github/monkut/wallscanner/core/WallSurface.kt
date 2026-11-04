package io.github.monkut.wallscanner.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One planar face of a wall and its place in the unfolded wall frame (#8 phase 2). The face has its own
 * [frame] (right, up, normal); the rigid 2D map ([cos], [sin], [tu], [tv]) turns its local (u, v) into the
 * unfolded (U, V) shared by every face, as if the wall were laid flat along its seams. A secondary face owns
 * the side of its [seam] line (in unfolded coordinates: a point and a direction) given by [side]; the primary
 * face has no seam and owns the rest.
 */
class Face(
    val plane: Plane,
    val cos: Float = 1f, val sin: Float = 0f, val tu: Float = 0f, val tv: Float = 0f,
    val seam: FloatArray? = null, val side: Float = 0f,
) {
    val frame = WallFrame(plane)

    fun toUnfolded(u: Float, v: Float, out: FloatArray) {
        out[0] = cos * u - sin * v + tu
        out[1] = sin * u + cos * v + tv
    }

    fun toLocalU(bigU: Float, bigV: Float): Float = cos * (bigU - tu) + sin * (bigV - tv)
    fun toLocalV(bigU: Float, bigV: Float): Float = -sin * (bigU - tu) + cos * (bigV - tv)

    /** Whether unfolded ([bigU], [bigV]) lies on this face's side of its seam; the primary face says yes to everything. */
    fun owns(bigU: Float, bigV: Float): Boolean {
        val s = seam ?: return true
        val cross = s[2] * (bigV - s[1]) - s[3] * (bigU - s[0])
        return cross * side > 0f
    }

    /** Signed angle, in degrees, between this face's normal and [other]'s. */
    fun angleTo(other: Face): Float {
        val dot = (plane.nx * other.plane.nx + plane.ny * other.plane.ny + plane.nz * other.plane.nz).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(dot).toDouble()).toFloat()
    }
}

/**
 * The wall as one or more planar faces unfolded into a single 2D frame, so holds, the orthophoto and the route
 * image keep one (u, v) layout while every point is measured against the face it lies on. A single face is
 * exactly the old [WallFrame]: [u], [v] and [d] are the same numbers. With more faces a point belongs to the
 * secondary face whose seam side it is on (the one it is closest to when several claim it), else to the primary.
 */
class WallSurface(val faces: List<Face>) {
    init { require(faces.isNotEmpty()) }

    val primary: Face get() = faces[0]
    /** The primary face's frame: what scene.json's `wall` object and single-plane code keep using. */
    val frame: WallFrame get() = primary.frame
    val plane: Plane get() = primary.plane
    val multi: Boolean get() = faces.size > 1

    /** Unfolded U, V and the distance in front of the owning face, into [out] (size ≥ 3). */
    fun project(x: Float, y: Float, z: Float, out: FloatArray) {
        if (!multi) {
            val f = primary.frame
            out[0] = f.u(x, y, z); out[1] = f.v(x, y, z); out[2] = f.d(x, y, z)
            return
        }
        var bestD = Float.MAX_VALUE
        var found = false
        for (i in 1 until faces.size) {
            val face = faces[i]
            val f = face.frame
            val lu = f.u(x, y, z); val lv = f.v(x, y, z)
            val bu = face.cos * lu - face.sin * lv + face.tu
            val bv = face.sin * lu + face.cos * lv + face.tv
            if (!face.owns(bu, bv)) continue
            val d = f.d(x, y, z)
            if (abs(d) < abs(bestD)) { bestD = d; out[0] = bu; out[1] = bv; out[2] = d; found = true }
        }
        if (!found) {
            val f = primary.frame
            out[0] = f.u(x, y, z); out[1] = f.v(x, y, z); out[2] = f.d(x, y, z)
        }
    }

    private val scratch = ThreadLocal.withInitial { FloatArray(3) }

    fun u(x: Float, y: Float, z: Float): Float { val o = scratch.get(); project(x, y, z, o); return o[0] }
    fun v(x: Float, y: Float, z: Float): Float { val o = scratch.get(); project(x, y, z, o); return o[1] }
    fun d(x: Float, y: Float, z: Float): Float { val o = scratch.get(); project(x, y, z, o); return o[2] }

    /** The face that owns unfolded ([bigU], [bigV]): the first secondary whose seam side it is on, else the primary. */
    fun faceAt(bigU: Float, bigV: Float): Face {
        for (i in 1 until faces.size) if (faces[i].owns(bigU, bigV)) return faces[i]
        return primary
    }

    /** World point at unfolded ([bigU], [bigV]), [d] in front of the face there. */
    fun world(bigU: Float, bigV: Float, d: Float): FloatArray {
        val face = faceAt(bigU, bigV)
        val lu = face.toLocalU(bigU, bigV); val lv = face.toLocalV(bigU, bigV)
        val p = face.plane; val f = face.frame
        return floatArrayOf(
            p.ox + lu * f.rx + lv * f.ux + d * p.nx,
            p.oy + lu * f.ry + lv * f.uy + d * p.ny,
            p.oz + lu * f.rz + lv * f.uz + d * p.nz,
        )
    }

    companion object {
        fun single(frame: WallFrame): WallSurface = WallSurface(listOf(Face(frame.plane)))

        /**
         * A surface of [primary] plus [secondaries], each unfolded about its seam with the primary: the seam (the
         * two planes' intersection line) lands on the same unfolded line from both sides, and the secondary's
         * points land on the far side of it from the primary's centroid [towardU], [towardV]. A secondary whose
         * plane is parallel to the primary is dropped.
         */
        fun unfold(primary: Plane, secondaries: List<Plane>): WallSurface {
            val base = Face(primary)
            val pf = base.frame
            val faces = ArrayList<Face>()
            faces.add(base)
            for (plane in secondaries) {
                val f = WallFrame(plane)
                // Seam: direction n1 × n2; a point on both planes, stepped from the primary origin within the primary plane.
                val tx = pf.plane.ny * plane.nz - pf.plane.nz * plane.ny
                val ty = pf.plane.nz * plane.nx - pf.plane.nx * plane.nz
                val tz = pf.plane.nx * plane.ny - pf.plane.ny * plane.nx
                val tl = sqrt(tx * tx + ty * ty + tz * tz)
                if (tl < 1e-4f) continue
                val n1n2 = pf.plane.nx * plane.nx + pf.plane.ny * plane.ny + pf.plane.nz * plane.nz
                val mx = plane.nx - n1n2 * pf.plane.nx; val my = plane.ny - n1n2 * pf.plane.ny; val mz = plane.nz - n1n2 * pf.plane.nz
                val mn = mx * plane.nx + my * plane.ny + mz * plane.nz
                if (abs(mn) < 1e-6f) continue
                val s = ((plane.ox - pf.plane.ox) * plane.nx + (plane.oy - pf.plane.oy) * plane.ny + (plane.oz - pf.plane.oz) * plane.nz) / mn
                val ax = pf.plane.ox + s * mx; val ay = pf.plane.oy + s * my; val az = pf.plane.oz + s * mz
                val bx = ax + tx / tl; val by = ay + ty / tl; val bz = az + tz / tl
                // The seam in the primary's (u, v) and in the secondary's local (u, v).
                val au = pf.u(ax, ay, az); val av = pf.v(ax, ay, az); val bu = pf.u(bx, by, bz); val bv = pf.v(bx, by, bz)
                val au2 = f.u(ax, ay, az); val av2 = f.v(ax, ay, az); val bu2 = f.u(bx, by, bz); val bv2 = f.v(bx, by, bz)
                val dirU = bu - au; val dirV = bv - av
                var theta = atan2(dirV, dirU) - atan2(bv2 - av2, bu2 - au2)
                var c = cos(theta); var sn = sin(theta)
                var tu = au - (c * au2 - sn * av2); var tv = av - (sn * au2 + c * av2)
                // The secondary's own origin must unfold to the far side of the seam from the primary's origin (u, v) = (0, 0).
                val ou2 = f.u(plane.ox, plane.oy, plane.oz); val ov2 = f.v(plane.ox, plane.oy, plane.oz)
                val ouU = c * ou2 - sn * ov2 + tu; val ouV = sn * ou2 + c * ov2 + tv
                val sideOf = { pu: Float, pv: Float -> dirU * (pv - av) - dirV * (pu - au) }
                val primarySide = sideOf(0f, 0f)
                if (sideOf(ouU, ouV) * primarySide > 0f) {
                    theta += Math.PI.toFloat()
                    c = cos(theta); sn = sin(theta)
                    tu = au - (c * au2 - sn * av2); tv = av - (sn * au2 + c * av2)
                }
                val side = if (primarySide > 0f) -1f else 1f
                faces.add(Face(plane, c, sn, tu, tv, floatArrayOf(au, av, dirU, dirV), side))
            }
            return WallSurface(faces)
        }
    }
}
