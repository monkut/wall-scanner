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

    /** Points with x in [xMin, xMax]; the default covers the whole wall. */
    fun cloud(xMin: Float = 0f, xMax: Float = 3f): VoxelCloud {
        val map = VoxelMap()
        val rnd = Random(1)
        val noise = { (rnd.nextFloat() - 0.5f) * 0.004f }
        var x = 0f
        while (x <= 3f) {
            if (x in xMin..xMax) {
                var y = 0f
                while (y <= 2.5f) {
                    map.add(x, y, noise(), 150, 150, 150)
                    y += 0.005f
                }
            }
            x += 0.005f
        }
        for ((hx, hy, color) in holds) if (hx in xMin..xMax) addHemisphere(map, hx, hy, 0.06f, color, noise)
        return map.snapshot()
    }

    /** Hemisphere of radius [r] centred on (cx, cy), resting on the surface z = [base](x, y). */
    fun addHemisphere(
        map: VoxelMap, cx: Float, cy: Float, r: Float, color: IntArray, noise: () -> Float,
        base: (Float, Float) -> Float = { _, _ -> 0f },
    ) {
        val step = 0.004 / r
        var theta = 0.0
        while (theta < 2 * PI) {
            var phi = 0.0
            while (phi <= PI / 2) {
                val x = cx + r * cos(theta) * cos(phi)
                val y = cy + r * sin(theta) * cos(phi)
                val z = r * sin(phi) + base(x.toFloat(), y.toFloat())
                map.add(x.toFloat(), y.toFloat(), z.toFloat() + noise(), color[0], color[1], color[2])
                phi += step
            }
            theta += step
        }
    }
}
