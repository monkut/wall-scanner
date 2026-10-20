package io.github.monkut.wallscanner.core

import kotlin.math.sqrt

/** Plane through (ox, oy, oz) with unit normal (nx, ny, nz). */
class Plane(val ox: Float, val oy: Float, val oz: Float, nx: Float, ny: Float, nz: Float) {
    val nx: Float
    val ny: Float
    val nz: Float

    init {
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        this.nx = nx / len
        this.ny = ny / len
        this.nz = nz / len
    }

    fun signedDistance(x: Float, y: Float, z: Float): Float =
        (x - ox) * nx + (y - oy) * ny + (z - oz) * nz

    fun flipped() = Plane(ox, oy, oz, -nx, -ny, -nz)
}

/**
 * Orthonormal wall frame. `right` and `up` lie in the plane, the plane normal points toward the climber.
 * `up` is world Y (ARCore gravity up) projected into the plane.
 */
class WallFrame(val plane: Plane) {
    val ux: Float
    val uy: Float
    val uz: Float
    val rx: Float
    val ry: Float
    val rz: Float

    init {
        val n = plane
        var ax = 0f - 0f * n.nx - n.ny * n.nx
        var ay = 1f - n.ny * n.ny
        var az = 0f - n.ny * n.nz
        var len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 0.1f) {
            // horizontal plane: use world Z instead
            ax = 0f - n.nz * n.nx
            ay = 0f - n.nz * n.ny
            az = 1f - n.nz * n.nz
            len = sqrt(ax * ax + ay * ay + az * az)
        }
        ux = ax / len
        uy = ay / len
        uz = az / len
        // right = up x normal
        rx = uy * n.nz - uz * n.ny
        ry = uz * n.nx - ux * n.nz
        rz = ux * n.ny - uy * n.nx
    }

    fun u(x: Float, y: Float, z: Float): Float = (x - plane.ox) * rx + (y - plane.oy) * ry + (z - plane.oz) * rz
    fun v(x: Float, y: Float, z: Float): Float = (x - plane.ox) * ux + (y - plane.oy) * uy + (z - plane.oz) * uz
    fun d(x: Float, y: Float, z: Float): Float = plane.signedDistance(x, y, z)
}

/** Packs integer grid coordinates into one Long key (21 bits per axis). */
object VoxelKey {
    private const val OFFSET = 1 shl 20
    private const val MASK = (1L shl 21) - 1

    fun pack(ix: Int, iy: Int, iz: Int): Long =
        (((ix + OFFSET).toLong() and MASK) shl 42) or
            (((iy + OFFSET).toLong() and MASK) shl 21) or
            ((iz + OFFSET).toLong() and MASK)
}
