package io.github.monkut.wallscanner.core

/** All reconstruction thresholds in one place. Distances are meters unless noted. */
object Tuning {
    // Capture
    const val VOXEL_SIZE = 0.01f
    // Per segment; least-hit voxels are evicted beyond this (VoxelMap). Sized to fit a 2^21-slot table at
    // VoxelMap.MAX_LOAD (~75 MB; a rebuild briefly holds two tables, ~150 MB). VoxelMapTest pins the slot count.
    const val MAX_VOXELS = 1_400_000
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

    // Holds (HoldDetector: 2-D median relief map in wall cells, split into prominent peaks)
    const val MIN_HOLD_HEIGHT = 0.02f
    const val MAX_HOLD_HEIGHT = 0.8f // volumes and the holds mounted on them count (0.5 lost the top-corner volume)
    const val CLUSTER_CELL = 0.02f // relief-map cell; the wall's own depth noise is ~2 cm std per voxel
    const val HOLD_RELIEF_MIN = -0.3f // voxels outside this plane-distance window (behind the wall, people,
    const val HOLD_RELIEF_MAX = 1.0f // the floor) do not vote in a cell's median
    const val MIN_HOLD_CELLS = 4
    const val MIN_HOLD_EXTENT = 0.03f
    const val MAX_HOLD_EXTENT = 1.2f // large slopers and volumes; mats and side walls come out wider
    const val STEP_MIN_EXTENT = 0.6f // a component this wide whose cells inside the wall's extent reach MAX_HOLD_HEIGHT is
    // the rim of a step of the wall (a boxed-out section, a hangboard panel), not a hold: a deep jug is never this wide,
    // and the holds on a 0.8 m corner volume (Oct 21 6:21 PM) are 0.46 and 0.54 m across
    const val HOLD_MIN_PEAK = 0.03f // a component whose highest cell is lower than this is wall speckle
    const val HOLD_CORE_FRACTION = 0.5f // colour and centre come from cells at least this fraction of the peak
    /** A component is re-thresholded at each of these to find peaks that merged through their skirts. */
    val HOLD_SPLIT_LEVELS = floatArrayOf(0.02f, 0.03f, 0.04f, 0.05f, 0.06f, 0.08f, 0.10f, 0.13f, 0.16f, 0.20f, 0.25f, 0.30f, 0.40f, 0.50f, 0.60f, 0.70f)
    const val HOLD_MIN_PROMINENCE = 0.03f // a peak counts only if it rises this far above the split level,
    const val HOLD_PROMINENCE_FRACTION = 0.5f // or this fraction of the level, whichever is larger (0.3 split big jugs in two) …
    const val HOLD_PROMINENCE_FRACTION_UNTIL = 0.10f // … up to this level; above it a hold on a volume only needs
    const val HOLD_PROMINENCE_HIGH = 0.04f // this much above the volume's surface
    const val MIN_HOLD_VOXELS = 10 // voxels in the hold's core; fewer is a depth streak at a mat or wall edge
    // Colour growth of a coloured hold's footprint (flat slopers and jug edges sit at the depth noise floor)
    const val HOLD_GROW_MAX = 0.35f // how far beyond the relief component a hold may grow
    const val HOLD_GROW_HUE_TOL = 15f
    const val HOLD_GROW_VAL_TOL = 0.3f
    const val HOLD_GROW_MIN_RELIEF = -0.01f // cells behind the wall are never hold

    // Wall colour (WallColor): voxels and holds within these tolerances of the wall surface's colour are
    // treated as wall, not hold. Hue is ignored when the wall itself is grey (saturation under WALL_GREY_SAT).
    const val WALL_COLOR_BAND = 0.01f
    const val WALL_HUE_TOL = 10f
    const val WALL_SAT_TOL = 0.15f // minimum; widened to the wall's own interquartile range
    const val WALL_VAL_TOL = 0.1f // margin beyond the wall's 10th..90th percentile of value
    const val WALL_GREY_SAT = 0.15f

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
    const val MAX_ROUTE_HUE_SPAN = 35f // a hue cluster wider than this (red chained to orange) splits at its largest gap
    const val PALE_MIN_SAT = 0.2f // a pale hold (under MIN_ROUTE_SATURATION) may join a hue cluster from this saturation …
    const val PALE_MIN_CHROMA = 0.1f // … when saturation × value shows a hue (a dark hold's hue is noise) …
    const val PALE_HUE_TOL = 15f // … within this of the cluster's hue range …
    const val PALE_WALL_HUE_TOL = 20f // … and not this close to a coloured wall's hue (bare wall bled into the hold)
    const val MIN_ROUTE_HOLDS = 2
    const val MAX_ROUTE_HOLD_GAP = 1.4f // circuit walls: holds of one colour further apart than this are different problems
}
