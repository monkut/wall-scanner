package io.github.monkut.wallscanner.core

import kotlin.random.Random

class SceneResult(
    val frame: WallFrame,
    val planeInliers: Int,
    val holds: List<Hold>,
    val routes: List<Route>,
    val definition: Definition = Definition(),
)

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
    ): SceneResult? {
        if (cloud.count == 0) return null
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
        val frame = WallFrame(fit.plane)
        val holds = HoldDetector.detect(cloud, frame, localRelief = type.outdoor)
        val routes = if (type.colorIsRoute) RouteGrouper.group(holds, type.splitByPosition) else emptyList()
        return SceneResult(frame, fit.inliers, holds, routes, definition)
    }
}
