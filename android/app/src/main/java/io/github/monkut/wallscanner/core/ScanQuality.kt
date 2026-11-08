package io.github.monkut.wallscanner.core

import java.util.Locale

/**
 * How far a processed scan can be trusted (#35). ARCore can drift during a scan without reporting a tracking
 * loss: the wall is laid down twice, offset, so the orthophoto repeats itself and every hold comes out as two.
 * Three numbers that fall out of processing tell such a scan apart. The share of the cloud lying on the wall
 * surface (within [Tuning.PLANE_INLIER_DIST] of any of its faces) drops, since the smeared copy lies off it,
 * and the holds per square metre of wall double: both must fail ([Tuning.DRIFT_MAX_PLANE_SHARE],
 * [Tuning.DRIFT_MIN_HOLD_DENSITY]) for [smeared], since a wall of many volumes lowers the share alone and a
 * busy spray wall raises the density alone. And the keyframes disagree: where two comparable views of a pixel
 * differ by more than [Tuning.GHOST_DE] ([Orthophoto.ghost]) over [Tuning.GHOST_MIN_SHARE] of the wall and over
 * [Tuning.GHOST_CELL_SHARE] of some cell of its 3 × 3 grid, the scan is [ghosted] there, and [ghostCells] says
 * where to rescan.
 */
class ScanQuality(val planeShare: Float, val holdDensity: Float, val ghostShare: Float = Float.NaN, val ghostCells: List<Coverage.Cell> = emptyList()) {
    val smeared: Boolean get() = planeShare < Tuning.DRIFT_MAX_PLANE_SHARE && holdDensity > Tuning.DRIFT_MIN_HOLD_DENSITY
    val ghosted: Boolean get() = ghostShare >= Tuning.GHOST_MIN_SHARE && ghostCells.isNotEmpty()
    val drifted: Boolean get() = smeared || ghosted

    override fun toString(): String =
        String.format(Locale.US, "plane share %.1f%% holds %.1f/m²", planeShare * 100, holdDensity) +
            (if (ghostShare.isNaN()) "" else String.format(Locale.US, " ghost %.1f%%", ghostShare * 100) + (if (ghostCells.isEmpty()) "" else ghostCells.joinToString(prefix = " in ") { "(row ${it.row}, col ${it.col})" })) +
            when { smeared && ghosted -> " → drifted (smeared, ghosted)"; smeared -> " → drifted (smeared)"; ghosted -> " → drifted (ghosted)"; else -> "" }

    companion object {
        private const val CELL_MIN_SEEN = 0.15f // of the median cell's compared pixels, as Coverage judges thin depth

        /** From [onSurface] of [pointCount] voxels on the wall and [holdCount] holds on the wall [extent] (minU, minV, maxU, maxV). */
        fun of(onSurface: Int, pointCount: Int, holdCount: Int, extent: FloatArray, ghostShare: Float = Float.NaN, ghostCells: List<Coverage.Cell> = emptyList()): ScanQuality {
            val area = (extent[2] - extent[0]) * (extent[3] - extent[1])
            return ScanQuality(if (pointCount > 0) onSurface.toFloat() / pointCount else 0f, if (area > 0f) holdCount / area else 0f, ghostShare, ghostCells)
        }

        /** Counts the voxels of [cloud] within [Tuning.PLANE_INLIER_DIST] of [surface], every face included, and reads the ghost map of [photo]. */
        fun of(cloud: VoxelCloud, surface: WallSurface, holdCount: Int, extent: FloatArray, photo: Orthophoto? = null): ScanQuality {
            val xyz = cloud.xyz
            val p = FloatArray(3)
            var onSurface = 0
            for (i in 0 until cloud.count) {
                surface.project(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], p)
                if (p[2] > -Tuning.PLANE_INLIER_DIST && p[2] < Tuning.PLANE_INLIER_DIST) onSurface++
            }
            val (share, cells) = ghost(photo, extent)
            return of(onSurface, cloud.count, holdCount, extent, share, cells)
        }

        /**
         * The share of [photo]'s compared pixels inside [extent] whose two views disagree past [Tuning.GHOST_DE],
         * and the cells of the [Coverage] grid where that share passes [Tuning.GHOST_CELL_SHARE]. A cell with
         * fewer compared pixels than [CELL_MIN_SEEN] of the median cell is not named: two of three pixels are
         * not a region. NaN and none without a ghost map.
         */
        fun ghost(photo: Orthophoto?, extent: FloatArray): Pair<Float, List<Coverage.Cell>> {
            val g = photo?.ghost ?: return Float.NaN to emptyList()
            val w = photo.image.width
            val seen = IntArray(Coverage.ROWS * Coverage.COLS); val high = IntArray(Coverage.ROWS * Coverage.COLS)
            for (i in g.indices) {
                if (g[i].isNaN()) continue
                val k = Coverage.cellOf(photo.u(i % w + 0.5f), photo.v(i / w + 0.5f), extent)
                if (k < 0) continue
                seen[k]++
                if (g[i] > Tuning.GHOST_DE) high[k]++
            }
            val total = seen.sum()
            if (total == 0) return Float.NaN to emptyList()
            val minSeen = CELL_MIN_SEEN * seen.sorted()[seen.size / 2]
            val cells = seen.indices.filter { seen[it] > 0 && seen[it] >= minSeen && high[it] >= Tuning.GHOST_CELL_SHARE * seen[it] }.map { Coverage.Cell(it / Coverage.COLS, it % Coverage.COLS) }
            return high.sum().toFloat() / total to cells
        }
    }
}
