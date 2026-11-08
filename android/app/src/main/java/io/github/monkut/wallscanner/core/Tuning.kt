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
    // Scan guide (#36): cues while scanning, from the depth frame's central window and the camera's motion. Between
    // keyframes of the good noborock Main wall scans (Nov 5–6): 1.2–2.3 m from the wall (closest decile 0.64–2.2 m),
    // turned 13–24° from facing it about the vertical (median; 12:13, scanned from a corner, 58°; 90th percentiles
    // 33–64°), sweeping 0.6–1 m/s across the wall (median; 90th percentiles 1.0–1.6 m/s). The drifted scan of
    // 2026-11-06 12:25 spent its middle 0.4 m from the wall and behind it; its speed was no different.
    const val GUIDE_MIN_DISTANCE = 0.8f
    const val GUIDE_FAR_FRACTION = 0.85f // of the depth range in use: 3 m of a gym's 3.5 m, 4.3 m of the outdoor 5 m
    const val GUIDE_MAX_FACING_DEG = 50f // turned along the wall or at a side wall; the look up at the top row is not counted
    const val GUIDE_MAX_SPEED = 2f // across the wall, smoothed over about a second: swinging the phone, not the sweep

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
    const val STEP_BOTTOM_MARGIN = 0.15f // a step reaching this close to the extent's bottom is the mat: claimed, not reported (#28)
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
    const val PALE_ROUTE_MIN_HOLDS = 5 // pale holds left over after attachPale form a route of their own when a route's worth agree in hue …
    const val PALE_ROUTE_MIN_CHROMA = 0.08f // … at this saturation × value. Grey holds under gym light read bluish or purplish at 0.2–0.3
    // saturation too (Oct 23 references: 3–6 of them agree within 30°, chroma 0.06–0.09); the 12 dusty purples of noborock 1F
    // (2026-11-05, #27) are at chroma 0.07–0.14, 9 of them above 0.08
    const val START_REACH = 0.8f // a tape-marked start hold's foot holds lie below it within this much to either side (#27)
    const val DOWN_CLIMB_MIN_WALL_HEIGHT = 3.4f // walls this tall carry grey down-climb holds (noborock 1F, 3.54 m); the 3.2 m walls of
    // Oct 29 have no grey route at all, and the grey group of a shorter wall may be a route
    // A down-climb hold is a grey hand-sized jug on the upper wall (the climber's rules, #27, Nov 5 scans): never a START hold, …
    const val DOWN_CLIMB_TOP_SHARE = 2f / 3f // … only in this upper share of the wall's height (1F #1 sat at the mat line) …
    const val DOWN_CLIMB_MAX_SIDE = 0.3f // … never a bar or volume longer than this (Main wall 0.70 × 0.61 and 0.42 × 0.40 m were black) …
    const val DOWN_CLIMB_MIN_SIDE = 0.08f // … nor a chip narrower than this (1F 0.12 × 0.07 m was black); the real ones are 0.10–0.25 m …
    const val DOWN_CLIMB_MIN_VALUE = 0.35f // … and light: darker grey-blue holds (1F 0.27–0.30, Main 0.26–0.30) were black, the real ones 0.39–0.60
    const val DOWN_CLIMB_DARK_VALUE = 0.45f // a grey hold that fails those tests is Black under this value (1F 0.43–0.44 were black), else Gray
    const val PALE_ROUTE_JOIN_SAT = 0.12f // a route of pale holds takes the leftover holds of about its hue from this saturation …
    const val PALE_ROUTE_JOIN_TOL = 25f // … within this of its hue range: the 1F dusty purples the climber labelled span 242–282° at 0.13–0.30
    const val DRIFT_MAX_PLANE_SHARE = 0.11f // a scan with under this share of its cloud within PLANE_INLIER_DIST of the wall surface AND …
    const val DRIFT_MIN_HOLD_DENSITY = 10f // … over this many holds per m² of wall was smeared by tracking drift (#35): the 11 good
    // noborock scans of Oct 23 – Nov 6 score 15.7–37.5% (every face counted; 12:21's three faces: 35.6%) and 3.6–7.6/m², the drifted
    // Main wall scan of 2026-11-06 12:25 8.8% and 14.6/m²
    const val GHOST_DE = 25f // two comparable keyframe views of a wall pixel that differ by this much CIE ΔE disagree (Orthophoto.ghost) …
    const val GHOST_MIN_SHARE = 0.12f // … over this share of the wall's compared pixels AND …
    const val GHOST_CELL_SHARE = 0.3f // … over this share of some cell of the 3 × 3 grid is a ghosted scan (#35): the six good noborock scans
    // of Nov 5–6 disagree on 3.6–9.3% overall with their worst cell at 9–23%; the drifted 12:25 scan on 16.1%, with 31–53% in the three
    // cells where its orthophoto repeats itself
    const val MIN_ROUTE_HOLDS = 2
    const val MAX_ROUTE_HOLD_GAP = 1.4f // circuit walls: holds of one colour further apart than this are different problems
}
