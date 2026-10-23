package io.github.monkut.wallscanner.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic wall: plane z = 0 facing +z, 3 × 2.5 m gray panel with nine hemispherical holds in three colors. */
object SyntheticWall {
    val red = intArrayOf(220, 30, 30)
    val blue = intArrayOf(30, 60, 220)
    val yellow = intArrayOf(235, 210, 30)
    val holds = listOf(
        Triple(0.5f, 0.5f, red), Triple(1.5f, 0.9f, red), Triple(2.5f, 1.4f, red),
        Triple(0.8f, 1.6f, blue), Triple(1.8f, 0.4f, blue), Triple(2.2f, 2.1f, blue),
        Triple(0.4f, 2.2f, yellow), Triple(1.2f, 1.3f, yellow), Triple(2.7f, 0.6f, yellow),
    )
    val cameraInFront = floatArrayOf(1.5f, 1.2f, 2f)

    val gray = intArrayOf(150, 150, 150)

    /** Points with x in [xMin, xMax]; the default covers the whole wall. */
    fun cloud(xMin: Float = 0f, xMax: Float = 3f): VoxelCloud {
        val map = VoxelMap()
        val rnd = Random(1)
        val noise = { (rnd.nextFloat() - 0.5f) * 0.004f }
        addPanel(map, 3f, 2.5f, noise, xMin, xMax, holds.map { (hx, hy, _) -> Triple(hx, hy, 0.06f) })
        for ((hx, hy, color) in holds) if (hx in xMin..xMax) addHemisphere(map, hx, hy, 0.06f, color, noise)
        return map.snapshot()
    }

    /**
     * Gray panel [width] × [height] on z = 0 sampled every 5 mm, restricted to x in [xMin, xMax]. The wall
     * under a hold is never seen by a depth camera, so [holes] (cx, cy, radius) are left unsampled.
     */
    fun addPanel(
        map: VoxelMap, width: Float, height: Float, noise: () -> Float, xMin: Float = 0f, xMax: Float = width,
        holes: List<Triple<Float, Float, Float>> = emptyList(),
    ) {
        var x = 0f
        while (x <= width) {
            if (x in xMin..xMax) {
                var y = 0f
                while (y <= height) {
                    val covered = holes.any { (hx, hy, r) -> (x - hx) * (x - hx) + (y - hy) * (y - hy) < r * r }
                    if (!covered) map.add(x, y, noise(), gray[0], gray[1], gray[2])
                    y += 0.005f
                }
            }
            x += 0.005f
        }
    }

    /**
     * Hemisphere of radius [r] centred on (cx, cy), resting on the surface z = [base](x, y), coloured [color]
     * or, when given, [colorAt] the point's height above the base.
     */
    fun addHemisphere(
        map: VoxelMap, cx: Float, cy: Float, r: Float, color: IntArray, noise: () -> Float,
        base: (Float, Float) -> Float = { _, _ -> 0f },
        colorAt: (Float) -> IntArray = { color },
    ) {
        val step = 0.004 / r
        var theta = 0.0
        while (theta < 2 * PI) {
            var phi = 0.0
            while (phi <= PI / 2) {
                val x = cx + r * cos(theta) * cos(phi)
                val y = cy + r * sin(theta) * cos(phi)
                val height = (r * sin(phi)).toFloat()
                val z = height + base(x.toFloat(), y.toFloat())
                val c = colorAt(height)
                map.add(x.toFloat(), y.toFloat(), z + noise(), c[0], c[1], c[2])
                phi += step
            }
            theta += step
        }
    }
}
