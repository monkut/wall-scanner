package io.github.monkut.wallscanner.core

/** All reconstruction thresholds in one place. Distances are meters unless noted. */
object Tuning {
    // Capture
    const val VOXEL_SIZE = 0.01f
    const val MAX_VOXELS = 400_000
    const val MAX_DEPTH_RANGE = 5f
    const val MIN_DEPTH_CONFIDENCE = 128 // raw depth confidence, 0..255
    const val MAX_SAMPLES_PER_DEPTH_FRAME = 30_000

    // Wall plane
    const val RANSAC_ITERATIONS = 600
    const val RANSAC_SAMPLE_POINTS = 60_000
    const val PLANE_INLIER_DIST = 0.02f
    const val MAX_WALL_NORMAL_Y = 0.7f // rejects near-horizontal planes (floor, roof)
    const val PLANE_REFINE_ITERATIONS = 100

    // Holds
    const val MIN_HOLD_HEIGHT = 0.02f
    const val MAX_HOLD_HEIGHT = 0.5f
    const val CLUSTER_CELL = 0.02f
    const val MIN_HOLD_VOXELS = 10
    const val MIN_HOLD_EXTENT = 0.02f
    const val MAX_HOLD_EXTENT = 0.8f

    // Routes
    const val MIN_ROUTE_SATURATION = 0.3f
    const val MIN_ROUTE_VALUE = 0.15f
    const val HUE_GAP_DEG = 30f
    const val MIN_ROUTE_HOLDS = 2
}
