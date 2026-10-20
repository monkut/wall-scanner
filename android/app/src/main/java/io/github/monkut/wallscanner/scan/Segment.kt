package io.github.monkut.wallscanner.scan

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.monkut.wallscanner.core.VoxelMap

/**
 * One contiguous tracking interval of a scan. Points and camera positions are stored relative to [anchor],
 * so when ARCore relocalises or corrects drift the whole segment follows the anchor instead of staying
 * at stale world coordinates.
 */
class Segment(val id: Int, val anchor: Anchor) {
    val map = VoxelMap()
    val cameraPositions = ArrayList<FloatArray>() // anchor-local
    val renderer = PointsRenderer()

    /** World-from-anchor pose at the last frame on which the anchor was TRACKING. */
    @Volatile
    var anchorPose: Pose = anchor.pose
        private set

    @Volatile
    var anchorState: TrackingState = anchor.trackingState
        private set

    /** Column-major matrix of [anchorPose]; written and read on the GL thread only. */
    val anchorMatrix = FloatArray(16)
    var uploadedVoxels = -1

    init { anchorPose.toMatrix(anchorMatrix, 0) }

    /** Refreshes the cached anchor pose; call once per frame on the GL thread. */
    fun refreshAnchor() {
        anchorState = anchor.trackingState
        if (anchorState == TrackingState.TRACKING) {
            anchorPose = anchor.pose
            anchorPose.toMatrix(anchorMatrix, 0)
        }
    }

    fun cameraPositions(): List<FloatArray> = synchronized(cameraPositions) { ArrayList(cameraPositions) }

    fun addCameraPosition(p: FloatArray) = synchronized(cameraPositions) { cameraPositions.add(p) }
}

/** Thread-safe ordered list of the segments of one scan. */
class SegmentList {
    private val items = ArrayList<Segment>()

    val size: Int get() = synchronized(items) { items.size }

    fun all(): List<Segment> = synchronized(items) { ArrayList(items) }

    fun add(segment: Segment) = synchronized(items) { items.add(segment) }

    fun totalVoxels(): Int = all().sumOf { it.map.size }
}
