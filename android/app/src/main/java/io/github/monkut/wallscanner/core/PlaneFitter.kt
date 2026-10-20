package io.github.monkut.wallscanner.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class PlaneFit(val plane: Plane, val inliers: Int)

/** RANSAC fit of the dominant near-vertical plane, refined by least squares and oriented toward the camera. */
object PlaneFitter {
    fun fit(cloud: VoxelCloud, random: Random, towardX: Float, towardY: Float, towardZ: Float): PlaneFit? {
        val n = cloud.count
        if (n < 100) return null
        val xyz = cloud.xyz
        val sampleCount = min(n, Tuning.RANSAC_SAMPLE_POINTS)
        val sample = IntArray(sampleCount) { if (sampleCount == n) it else random.nextInt(n) }

        var best: Plane? = null
        var bestInliers = 0
        repeat(Tuning.RANSAC_ITERATIONS) {
            val a = random.nextInt(n)
            val b = random.nextInt(n)
            val c = random.nextInt(n)
            if (a != b && b != c && a != c) {
                val ax = xyz[a * 3]; val ay = xyz[a * 3 + 1]; val az = xyz[a * 3 + 2]
                val e1x = xyz[b * 3] - ax; val e1y = xyz[b * 3 + 1] - ay; val e1z = xyz[b * 3 + 2] - az
                val e2x = xyz[c * 3] - ax; val e2y = xyz[c * 3 + 1] - ay; val e2z = xyz[c * 3 + 2] - az
                var nx = e1y * e2z - e1z * e2y
                var ny = e1z * e2x - e1x * e2z
                var nz = e1x * e2y - e1y * e2x
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 1e-6f) {
                    nx /= len; ny /= len; nz /= len
                    if (abs(ny) <= Tuning.MAX_WALL_NORMAL_Y) {
                        var inliers = 0
                        for (i in sample) {
                            val d = (xyz[i * 3] - ax) * nx + (xyz[i * 3 + 1] - ay) * ny + (xyz[i * 3 + 2] - az) * nz
                            if (abs(d) < Tuning.PLANE_INLIER_DIST) inliers++
                        }
                        if (inliers > bestInliers) {
                            bestInliers = inliers
                            best = Plane(ax, ay, az, nx, ny, nz)
                        }
                    }
                }
            }
        }
        val coarse = best ?: return null
        val refined = refine(cloud, coarse) ?: return null
        val plane = if (refined.plane.signedDistance(towardX, towardY, towardZ) < 0f) refined.plane.flipped() else refined.plane
        return PlaneFit(plane, refined.inliers)
    }

    /** Least-squares plane through the inliers of [coarse]: centroid + smallest covariance eigenvector. */
    private fun refine(cloud: VoxelCloud, coarse: Plane): PlaneFit? {
        val xyz = cloud.xyz
        val n = cloud.count
        var count = 0
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in 0 until n) {
            if (abs(coarse.signedDistance(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])) < Tuning.PLANE_INLIER_DIST) {
                count++
                sx += xyz[i * 3]; sy += xyz[i * 3 + 1]; sz += xyz[i * 3 + 2]
            }
        }
        if (count < 3) return null
        val cx = sx / count; val cy = sy / count; val cz = sz / count
        var xx = 0.0; var xy = 0.0; var xz = 0.0; var yy = 0.0; var yz = 0.0; var zz = 0.0
        for (i in 0 until n) {
            val x = xyz[i * 3]; val y = xyz[i * 3 + 1]; val z = xyz[i * 3 + 2]
            if (abs(coarse.signedDistance(x, y, z)) < Tuning.PLANE_INLIER_DIST) {
                val dx = x - cx; val dy = y - cy; val dz = z - cz
                xx += dx * dx; xy += dx * dy; xz += dx * dz
                yy += dy * dy; yz += dy * dz; zz += dz * dz
            }
        }
        // Smallest eigenvector of C via power iteration on (trace(C) * I - C), seeded with the RANSAC normal.
        val shift = xx + yy + zz
        val m00 = shift - xx; val m11 = shift - yy; val m22 = shift - zz
        val m01 = -xy; val m02 = -xz; val m12 = -yz
        var vx = coarse.nx.toDouble(); var vy = coarse.ny.toDouble(); var vz = coarse.nz.toDouble()
        repeat(Tuning.PLANE_REFINE_ITERATIONS) {
            val nx = m00 * vx + m01 * vy + m02 * vz
            val ny = m01 * vx + m11 * vy + m12 * vz
            val nz = m02 * vx + m12 * vy + m22 * vz
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12) { vx = nx / len; vy = ny / len; vz = nz / len }
        }
        return PlaneFit(Plane(cx.toFloat(), cy.toFloat(), cz.toFloat(), vx.toFloat(), vy.toFloat(), vz.toFloat()), count)
    }
}
