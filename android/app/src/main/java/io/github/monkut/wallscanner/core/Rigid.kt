package io.github.monkut.wallscanner.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Rigid transform p' = R p + t with [r] a row-major 3x3 rotation and [t] a translation. */
class Rigid(val r: DoubleArray, val t: DoubleArray) {
    fun apply(x: Double, y: Double, z: Double, out: DoubleArray) {
        out[0] = r[0] * x + r[1] * y + r[2] * z + t[0]
        out[1] = r[3] * x + r[4] * y + r[5] * z + t[1]
        out[2] = r[6] * x + r[7] * y + r[8] * z + t[2]
    }

    fun apply(p: FloatArray): FloatArray {
        val o = DoubleArray(3)
        apply(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), o)
        return floatArrayOf(o[0].toFloat(), o[1].toFloat(), o[2].toFloat())
    }

    fun apply(cloud: VoxelCloud): VoxelCloud {
        val xyz = FloatArray(cloud.count * 3)
        val o = DoubleArray(3)
        for (i in 0 until cloud.count) {
            apply(cloud.xyz[i * 3].toDouble(), cloud.xyz[i * 3 + 1].toDouble(), cloud.xyz[i * 3 + 2].toDouble(), o)
            xyz[i * 3] = o[0].toFloat(); xyz[i * 3 + 1] = o[1].toFloat(); xyz[i * 3 + 2] = o[2].toFloat()
        }
        return VoxelCloud(cloud.count, xyz, cloud.rgb, cloud.hits)
    }

    /** this ∘ [other]: applies [other] first, then this. */
    fun compose(other: Rigid): Rigid {
        val m = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            m[i * 3 + j] = r[i * 3] * other.r[j] + r[i * 3 + 1] * other.r[3 + j] + r[i * 3 + 2] * other.r[6 + j]
        }
        val tt = DoubleArray(3)
        apply(other.t[0], other.t[1], other.t[2], tt)
        return Rigid(m, tt)
    }

    fun inverse(): Rigid {
        val rt = doubleArrayOf(r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8])
        val tt = doubleArrayOf(
            -(rt[0] * t[0] + rt[1] * t[1] + rt[2] * t[2]),
            -(rt[3] * t[0] + rt[4] * t[1] + rt[5] * t[2]),
            -(rt[6] * t[0] + rt[7] * t[1] + rt[8] * t[2]),
        )
        return Rigid(rt, tt)
    }

    override fun toString(): String =
        "t=(${"%.3f".format(t[0])}, ${"%.3f".format(t[1])}, ${"%.3f".format(t[2])}) r=[" +
            r.joinToString(", ") { "%.4f".format(it) } + "]"

    companion object {
        val IDENTITY = Rigid(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), DoubleArray(3))

        fun translation(dx: Double, dy: Double, dz: Double) = Rigid(IDENTITY.r, doubleArrayOf(dx, dy, dz))

        /** Rotation by [angle] radians about the axis (ax, ay, az) through the pivot (px, py, pz). */
        fun rotationAbout(ax: Double, ay: Double, az: Double, angle: Double, px: Double, py: Double, pz: Double): Rigid {
            val len = sqrt(ax * ax + ay * ay + az * az)
            if (len < 1e-12) return IDENTITY
            val x = ax / len; val y = ay / len; val z = az / len
            val c = cos(angle); val s = sin(angle); val k = 1 - c
            val r = doubleArrayOf(
                c + x * x * k, x * y * k - z * s, x * z * k + y * s,
                y * x * k + z * s, c + y * y * k, y * z * k - x * s,
                z * x * k - y * s, z * y * k + x * s, c + z * z * k,
            )
            val rot = Rigid(r, DoubleArray(3))
            val rp = DoubleArray(3)
            rot.apply(px, py, pz, rp)
            return Rigid(r, doubleArrayOf(px - rp[0], py - rp[1], pz - rp[2]))
        }

        /** From an OpenGL column-major 4x4 matrix (what ARCore's Pose.toMatrix produces). */
        fun fromColumnMajor(m: FloatArray): Rigid {
            val r = DoubleArray(9)
            for (i in 0 until 3) for (j in 0 until 3) r[i * 3 + j] = m[j * 4 + i].toDouble()
            return Rigid(r, doubleArrayOf(m[12].toDouble(), m[13].toDouble(), m[14].toDouble()))
        }
    }
}
