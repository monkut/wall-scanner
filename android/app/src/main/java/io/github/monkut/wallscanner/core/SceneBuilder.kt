package io.github.monkut.wallscanner.core

import kotlin.random.Random

class SceneResult(val frame: WallFrame, val planeInliers: Int, val holds: List<Hold>, val routes: List<Route>)

/** Full offline pipeline: voxel cloud -> wall plane -> holds -> routes. */
object SceneBuilder {
    fun build(cloud: VoxelCloud, cameraPositions: List<FloatArray>, random: Random = Random(42)): SceneResult? {
        if (cloud.count == 0) return null
        var tx = 0f; var ty = 0f; var tz = 0f
        if (cameraPositions.isNotEmpty()) {
            for (p in cameraPositions) { tx += p[0]; ty += p[1]; tz += p[2] }
            tx /= cameraPositions.size; ty /= cameraPositions.size; tz /= cameraPositions.size
        }
        val fit = PlaneFitter.fit(cloud, random, tx, ty, tz) ?: return null
        val frame = WallFrame(fit.plane)
        val holds = HoldDetector.detect(cloud, frame)
        val routes = RouteGrouper.group(holds)
        return SceneResult(frame, fit.inliers, holds, routes)
    }
}
