package io.github.monkut.wallscanner.core

import java.util.Locale

/**
 * How far a processed scan can be trusted (#35). ARCore can drift during a scan without reporting a tracking
 * loss: the wall is laid down twice, offset, so the orthophoto repeats itself and every hold comes out as two.
 * Two numbers that fall out of processing tell such a scan apart: the share of the cloud lying on the wall
 * surface (within [Tuning.PLANE_INLIER_DIST] of any of its faces) drops, since the smeared copy lies off it, and
 * the holds per square metre of wall double. Both must fail ([Tuning.DRIFT_MAX_PLANE_SHARE],
 * [Tuning.DRIFT_MIN_HOLD_DENSITY]) for [drifted]: a wall of many volumes lowers the share alone, a busy spray
 * wall raises the density alone.
 */
class ScanQuality(val planeShare: Float, val holdDensity: Float) {
    val drifted: Boolean get() = planeShare < Tuning.DRIFT_MAX_PLANE_SHARE && holdDensity > Tuning.DRIFT_MIN_HOLD_DENSITY

    override fun toString(): String =
        String.format(Locale.US, "plane share %.1f%% holds %.1f/m²", planeShare * 100, holdDensity) + if (drifted) " → drifted" else ""

    companion object {
        /** From [onSurface] of [pointCount] voxels on the wall and [holdCount] holds on the wall [extent] (minU, minV, maxU, maxV). */
        fun of(onSurface: Int, pointCount: Int, holdCount: Int, extent: FloatArray): ScanQuality {
            val area = (extent[2] - extent[0]) * (extent[3] - extent[1])
            return ScanQuality(if (pointCount > 0) onSurface.toFloat() / pointCount else 0f, if (area > 0f) holdCount / area else 0f)
        }

        /** Counts the voxels of [cloud] within [Tuning.PLANE_INLIER_DIST] of [surface], every face included. */
        fun of(cloud: VoxelCloud, surface: WallSurface, holdCount: Int, extent: FloatArray): ScanQuality {
            val xyz = cloud.xyz
            val p = FloatArray(3)
            var onSurface = 0
            for (i in 0 until cloud.count) {
                surface.project(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], p)
                if (p[2] > -Tuning.PLANE_INLIER_DIST && p[2] < Tuning.PLANE_INLIER_DIST) onSurface++
            }
            return of(onSurface, cloud.count, holdCount, extent)
        }
    }
}
