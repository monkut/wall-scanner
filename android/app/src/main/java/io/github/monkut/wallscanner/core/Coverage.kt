package io.github.monkut.wallscanner.core

/**
 * Where the scan has little depth. The wall extent is cut into [ROWS] × [COLS] cells (row 0 at the top, col
 * 0 at the left) and a cell with fewer near-wall points than [THIN_FRACTION] of the median cell is thin:
 * holds there come only from the photo, and the orthophoto's parallax correction has nothing to work with.
 * Typical cause: the sweep never pointed at the top corners.
 */
object Coverage {
    const val ROWS = 3
    const val COLS = 3
    private const val THIN_FRACTION = 0.15f
    private const val MIN_RELIEF = -0.1f
    private const val MAX_RELIEF = 0.8f

    class Cell(val row: Int, val col: Int)

    /** Index (row × [COLS] + col) of the grid cell holding wall point ([u], [v]) of [extent], or -1 outside it. */
    fun cellOf(u: Float, v: Float, extent: FloatArray): Int {
        val width = extent[2] - extent[0]; val height = extent[3] - extent[1]
        if (width <= 0f || height <= 0f) return -1
        val col = ((u - extent[0]) / width * COLS).toInt()
        val row = ((extent[3] - v) / height * ROWS).toInt()
        return if (col !in 0 until COLS || row !in 0 until ROWS) -1 else row * COLS + col
    }

    fun thinCells(cloud: VoxelCloud, surface: WallSurface, extent: FloatArray): List<Cell> {
        val counts = IntArray(ROWS * COLS)
        val xyz = cloud.xyz
        val p = FloatArray(3)
        for (i in 0 until cloud.count) {
            surface.project(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], p)
            if (p[2] < MIN_RELIEF || p[2] > MAX_RELIEF) continue
            val k = cellOf(p[0], p[1], extent)
            if (k >= 0) counts[k]++
        }
        val sorted = counts.copyOf(); sorted.sort()
        val median = sorted[sorted.size / 2]
        if (median == 0) return emptyList()
        return counts.indices.filter { counts[it] < THIN_FRACTION * median }.map { Cell(it / COLS, it % COLS) }
    }
}
