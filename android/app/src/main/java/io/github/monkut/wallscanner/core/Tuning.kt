package io.github.monkut.wallscanner.core

/** All reconstruction thresholds in one place. Distances are meters unless noted. */
object Tuning {
    // Capture
    const val VOXEL_SIZE = 0.01f
    const val MAX_VOXELS = 1_500_000 // per segment; least-hit voxels are evicted beyond this (VoxelMap)
    const val MIN_VOXEL_HITS = 2 // at Finish, voxels with fewer points are dropped as depth noise
    const val MAX_DEPTH_RANGE = 3.5f // gym walls; raw depth noise thickens the surface beyond this
    const val OUTDOOR_MAX_DEPTH_RANGE = 5f
    const val MIN_DEPTH_CONFIDENCE = 128 // raw depth confidence, 0..255
    const val MAX_SAMPLES_PER_DEPTH_FRAME = 30_000

    // Wall plane
    const val RANSAC_ITERATIONS = 600
    const val RANSAC_SAMPLE_POINTS = 60_000
    const val PLANE_INLIER_DIST = 0.02f
    const val MAX_WALL_NORMAL_Y = 0.7f // rejects near-horizontal planes (floor, roof)
    const val PLANE_REFINE_ITERATIONS = 100

    // Outdoor rock (RouteDefinition.outdoor): the reference plane is a coarse fit of the whole face, and hold
    // relief is measured against the local rock surface (per-cell median) instead of that plane.
    const val OUTDOOR_PLANE_INLIER_DIST = 0.10f
    const val OUTDOOR_MAX_WALL_NORMAL_Y = 0.95f // slabs down to ~18° above horizontal; ground still rejected
    const val OUTDOOR_RELIEF_CELL = 0.20f // larger than a hold, smaller than the rock's curvature

    // Holds
    const val MIN_HOLD_HEIGHT = 0.02f
    const val MAX_HOLD_HEIGHT = 0.5f
    const val CLUSTER_CELL = 0.02f
    const val MIN_HOLD_VOXELS = 10
    const val MIN_HOLD_EXTENT = 0.02f
    const val MAX_HOLD_EXTENT = 0.8f

    // Segment snap (registration of one tracking segment onto the rest of the scan)
    const val SNAP_MIN_VOXELS = 2000
    const val SNAP_CELL = 0.02f // relief-map cell; also the shift resolution
    const val SNAP_MAX_SHIFT = 0.5f // in-plane search radius
    const val SNAP_MAX_ROLL_DEG = 3f // ARCore keeps roll gravity-aligned, so only a small search is needed
    const val SNAP_ROLL_STEP_DEG = 0.5f
    const val SNAP_MIN_RELIEF = -0.03f // points this far behind the wall are ignored
    const val SNAP_MAX_RELIEF = 0.30f // points this far in front (people, floor) are ignored
    const val SNAP_MIN_OVERLAP_CELLS = 500 // 500 × 2 cm² = 0.2 m² of overlap
    const val SNAP_MIN_SCORE = 0.35f // normalised cross-correlation
    const val SNAP_MAX_TILT_DEG = 35f // larger plane disagreements are a different surface, not pose error
    const val SNAP_MAX_DEPTH = 0.6f

    // Routes
    const val MIN_ROUTE_SATURATION = 0.3f // hold colour is the saturated half of its voxels (HoldDetector); 0.25 only added noise routes
    const val MIN_ROUTE_VALUE = 0.15f
    const val HUE_GAP_DEG = 30f
    const val MIN_ROUTE_HOLDS = 2
    const val MAX_ROUTE_HOLD_GAP = 1.4f // circuit walls: holds of one colour further apart than this are different problems
}
