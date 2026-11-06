package io.github.monkut.wallscanner.core

import kotlin.random.Random

class SceneResult(
    /** The wall as one or more planar faces in one unfolded frame; [frame] is its primary face. */
    val surface: WallSurface,
    val planeInliers: Int,
    val holds: List<Hold>,
    val routes: List<Route>,
    val definition: Definition = Definition(),
    /** Mean colour of the wall surface; null for outdoor rock (holds are rock-coloured by nature). */
    val wallColor: WallColor? = null,
    /** Scanned wall area in the wall frame, [minU, minV, maxU, maxV] (1st..99th percentile of the points). */
    val extent: FloatArray = FloatArray(4),
) {
    val frame: WallFrame get() = surface.frame

    /** Orthophoto of the wall (wall.png) when keyframes allowed one; set by the scene pipeline after build. */
    var photo: Orthophoto? = null
    /** Cells of the wall with little depth (Coverage); set by the scene pipeline. */
    var thinCoverage: List<Coverage.Cell> = emptyList()
    /** The scan's quality verdict (ScanQuality, #35); set by the scene pipeline once the holds are final. */
    var quality: ScanQuality? = null
}

/**
 * Full offline pipeline: voxel cloud -> wall plane -> holds -> routes.
 * Route detection depends on [Definition.type]: colour grouping where hold colour identifies the route
 * (split by position on circuit walls); no automatic routes for tape, competition, free and board walls,
 * whose routes come from tape/placard detection or manual editing. Outdoor rock gets a coarse reference
 * plane and holds measured against the local rock surface; lines are marked by hand.
 */
object SceneBuilder {
    fun build(
        cloud: VoxelCloud,
        cameraPositions: List<FloatArray>,
        random: Random = Random(42),
        definition: Definition = Definition(),
        /** Called with 0..1 as the plane, the faces and the holds are found. */
        progress: (Float) -> Unit = {},
    ): SceneResult? {
        if (cloud.count == 0) return null
        progress(0f)
        var tx = 0f; var ty = 0f; var tz = 0f
        if (cameraPositions.isNotEmpty()) {
            for (p in cameraPositions) { tx += p[0]; ty += p[1]; tz += p[2] }
            tx /= cameraPositions.size; ty /= cameraPositions.size; tz /= cameraPositions.size
        }
        val type = definition.type
        val fit = (
            if (type.outdoor) PlaneFitter.fit(cloud, random, tx, ty, tz, Tuning.OUTDOOR_PLANE_INLIER_DIST, Tuning.OUTDOOR_MAX_WALL_NORMAL_Y)
            else PlaneFitter.fit(cloud, random, tx, ty, tz)
            ) ?: return null
        progress(0.3f)
        val frame = WallFrame(fit.plane)
        // A gym wall may be several faces (a top that kicks back, a corner); outdoor rock keeps its one coarse plane and local relief.
        val surface = if (type.outdoor) WallSurface.single(frame) else Facets.find(cloud, frame).surface
        val wall = if (type.outdoor) null else WallColor.of(cloud, surface)
        val extent = extent(cloud, surface)
        progress(0.45f)
        // Crash pads below the wall and beams above it reach the relief window; the wall surface itself ends at the extent.
        val holds = HoldDetector.detect(cloud, surface, localRelief = type.outdoor, wall = wall, wallExtent = extent)
            .filter { type.outdoor || (it.v >= extent[1] - EXTENT_HOLD_MARGIN && it.v <= extent[3] + EXTENT_HOLD_MARGIN) }
            .mapIndexed { i, h -> Hold(i + 1, h.cx, h.cy, h.cz, h.u, h.v, h.width, h.tall, h.height, h.r, h.g, h.b, h.voxels, h.outline) }
        progress(0.95f)
        val routes = if (type.colorIsRoute) RouteGrouper.group(holds, type.splitByPosition, wall, extent[3] - extent[1]) else emptyList()
        return SceneResult(surface, fit.inliers, holds, routes, definition, wall, extent)
    }

    /**
     * [result] with [holds] instead of its own: holds re-numbered bottom-up, left-to-right, routes regrouped
     * under the same definition. Used after image-only holds are added to the depth detections.
     */
    fun withHolds(result: SceneResult, holds: List<Hold>): SceneResult {
        val sorted = holds.sortedWith(compareBy({ it.v }, { it.u })).mapIndexed { i, h ->
            Hold(i + 1, h.cx, h.cy, h.cz, h.u, h.v, h.width, h.tall, h.height, h.r, h.g, h.b, h.voxels, h.outline).also { it.role = h.role; it.tape = h.tape }
        }
        val type = result.definition.type
        val routes = if (type.colorIsRoute) RouteGrouper.group(sorted, type.splitByPosition, result.wallColor, result.extent[3] - result.extent[1]) else emptyList()
        return SceneResult(result.surface, result.planeInliers, sorted, routes, result.definition, result.wallColor, result.extent).also {
            it.photo = result.photo
            it.thinCoverage = result.thinCoverage
            it.quality = result.quality
        }
    }

    /**
     * The same scene with its routes renumbered from [firstId]. Reprocess uses it so a new route never takes
     * the id of a route from the earlier scene: sends.json keys sends and ratings by route id.
     */
    fun withFirstRouteId(result: SceneResult, firstId: Int): SceneResult {
        val offset = firstId - 1
        if (offset <= 0) return result
        val routes = result.routes.map { r ->
            Route(r.id + offset, r.name, r.r, r.g, r.b, r.holdIds).also { it.grade = r.grade; it.gradeSystem = r.gradeSystem; it.confirmed = r.confirmed; it.kind = r.kind }
        }
        for (h in result.holds) {
            if (h.routeId != -1) h.routeId += offset
            h.routeIds.replaceAll { it + offset }
        }
        return SceneResult(result.surface, result.planeInliers, result.holds, routes, result.definition, result.wallColor, result.extent).also {
            it.photo = result.photo
            it.thinCoverage = result.thinCoverage
            it.quality = result.quality
        }
    }

    private const val EXTENT_SURFACE_BAND = 0.05f // only points on the wall surface define its edges
    private const val EXTENT_CROP = 0.01f
    private const val EXTENT_HOLD_MARGIN = 0.02f

    /** Wall-frame bounds of the points near the wall, trimmed of the stray 1% at each edge. */
    private fun extent(cloud: VoxelCloud, surface: WallSurface): FloatArray {
        val xyz = cloud.xyz
        val p = FloatArray(3)
        val us = FloatArray(cloud.count); val vs = FloatArray(cloud.count)
        var n = 0
        for (i in 0 until cloud.count) {
            surface.project(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], p)
            if (p[2] < -EXTENT_SURFACE_BAND || p[2] > EXTENT_SURFACE_BAND) continue
            us[n] = p[0]; vs[n] = p[1]; n++
        }
        if (n == 0) return FloatArray(4)
        us.copyOf(n).also { java.util.Arrays.sort(it); System.arraycopy(it, 0, us, 0, n) }
        vs.copyOf(n).also { java.util.Arrays.sort(it); System.arraycopy(it, 0, vs, 0, n) }
        val lo = (n * EXTENT_CROP).toInt(); val hi = n - 1 - lo
        return floatArrayOf(us[lo], vs[lo], us[hi], vs[hi])
    }
}
