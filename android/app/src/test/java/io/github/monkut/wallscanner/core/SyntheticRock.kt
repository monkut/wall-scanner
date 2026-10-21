package io.github.monkut.wallscanner.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic boulder face: an undulating surface z = A·sin(1.5x)·cos(1.2y) (±8 cm, facing +z) over 3 × 2.5 m
 * with four hemispherical bumps of rock colour sitting on it. A 2 cm plane fit cannot describe it; the local
 * relief in [HoldDetector] can.
 */
object SyntheticRock {
    private const val AMPLITUDE = 0.08f
    private val rock = intArrayOf(120, 110, 100)
    val bumps = listOf(Pair(0.6f, 0.5f), Pair(1.4f, 1.7f), Pair(2.2f, 0.9f), Pair(2.6f, 2.0f))

    fun surface(x: Float, y: Float): Float = AMPLITUDE * sin(1.5f * x) * cos(1.2f * y)

    fun cloud(): VoxelCloud {
        val map = VoxelMap()
        val rnd = Random(2)
        val noise = { (rnd.nextFloat() - 0.5f) * 0.004f }
        var x = 0f
        while (x <= 3f) {
            var y = 0f
            while (y <= 2.5f) {
                map.add(x, y, surface(x, y) + noise(), rock[0], rock[1], rock[2])
                y += 0.005f
            }
            x += 0.005f
        }
        for ((bx, by) in bumps) SyntheticWall.addHemisphere(map, bx, by, 0.06f, rock, noise, ::surface)
        return map.snapshot()
    }
}
