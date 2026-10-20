package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic wall: plane z = 0 facing +z, gray panel with nine hemispherical holds in three colors. */
class SceneBuilderTest {
    private val red = intArrayOf(220, 30, 30)
    private val blue = intArrayOf(30, 60, 220)
    private val yellow = intArrayOf(235, 210, 30)
    private val truth = listOf(
        Triple(0.5f, 0.5f, red), Triple(1.5f, 0.9f, red), Triple(2.5f, 1.4f, red),
        Triple(0.8f, 1.6f, blue), Triple(1.8f, 0.4f, blue), Triple(2.2f, 2.1f, blue),
        Triple(0.4f, 2.2f, yellow), Triple(1.2f, 1.3f, yellow), Triple(2.7f, 0.6f, yellow),
    )

    private fun buildCloud(): VoxelCloud {
        val map = VoxelMap()
        val rnd = Random(1)
        val noise = { (rnd.nextFloat() - 0.5f) * 0.004f }
        var x = 0f
        while (x <= 3f) {
            var y = 0f
            while (y <= 2.5f) {
                map.add(x, y, noise(), 150, 150, 150)
                y += 0.005f
            }
            x += 0.005f
        }
        for ((hx, hy, color) in truth) addHemisphere(map, hx, hy, 0.06f, color, noise)
        return map.snapshot()
    }

    private fun addHemisphere(map: VoxelMap, cx: Float, cy: Float, r: Float, color: IntArray, noise: () -> Float) {
        val step = 0.004 / r
        var theta = 0.0
        while (theta < 2 * PI) {
            var phi = 0.0
            while (phi <= PI / 2) {
                val x = cx + r * cos(theta) * cos(phi)
                val y = cy + r * sin(theta) * cos(phi)
                val z = r * sin(phi)
                map.add(x.toFloat(), y.toFloat(), z.toFloat() + noise(), color[0], color[1], color[2])
                phi += step
            }
            theta += step
        }
    }

    @Test
    fun detectsWallHoldsAndRoutes() {
        val cloud = buildCloud()
        val cameraInFront = listOf(floatArrayOf(1.5f, 1.2f, 2f))
        val result = SceneBuilder.build(cloud, cameraInFront, Random(7))
        assertTrue("plane fit failed", result != null)
        result!!

        val plane = result.frame.plane
        assertTrue("normal should face the camera (+z), got ${plane.nz}", plane.nz > 0.99f)
        assertTrue("plane offset should be ~0, got ${plane.signedDistance(1f, 1f, 0f)}", abs(plane.signedDistance(1f, 1f, 0f)) < 0.005f)
        assertTrue("wall up should follow world Y, got ${result.frame.uy}", result.frame.uy > 0.99f)

        assertEquals("hold count", truth.size, result.holds.size)
        for ((hx, hy, _) in truth) {
            val match = result.holds.any { abs(it.cx - hx) < 0.02f && abs(it.cy - hy) < 0.02f }
            assertTrue("no hold near ($hx, $hy)", match)
        }
        for (hold in result.holds) {
            assertTrue("hold width ${hold.width}", hold.width in 0.08f..0.14f)
            assertTrue("hold height ${hold.height}", hold.height in 0.05f..0.07f)
        }

        assertEquals("route count", 3, result.routes.size)
        assertEquals(setOf("Red", "Blue", "Yellow"), result.routes.map { it.name }.toSet())
        for (route in result.routes) assertEquals("holds in ${route.name}", 3, route.holdIds.size)
        assertTrue(result.holds.all { it.routeId != -1 })
    }

    @Test
    fun writesSceneJsonAndPly() {
        val cloud = buildCloud()
        val result = SceneBuilder.build(cloud, listOf(floatArrayOf(1.5f, 1.2f, 2f)), Random(7))!!
        val json = SceneJson.write(result, cloud.count, "2026-10-20T10:00:00+09:00", "test")
        assertTrue(json.contains("\"version\": 1"))
        assertTrue(json.contains("\"holds\": ["))
        assertTrue(json.contains("\"name\": \"Red\""))
        assertEquals(result.holds.size, Regex("\"routeId\"").findAll(json).count())

        val out = ByteArrayOutputStream()
        PlyWriter.write(cloud, out)
        val bytes = out.toByteArray()
        val header = String(bytes, 0, 200, Charsets.US_ASCII)
        assertTrue(header.startsWith("ply\nformat binary_little_endian 1.0\nelement vertex ${cloud.count}\n"))
        val headerLen = header.indexOf("end_header\n") + "end_header\n".length
        assertEquals(headerLen + cloud.count * PlyWriter.BYTES_PER_POINT, bytes.size)
    }

    @Test
    fun rejectsCloudWithoutWall() {
        val map = VoxelMap()
        val rnd = Random(3)
        repeat(5000) { map.add(rnd.nextFloat() * 3f, rnd.nextFloat() * 3f, rnd.nextFloat() * 3f, 100, 100, 100) }
        val result = SceneBuilder.build(map.snapshot(), emptyList(), Random(7))
        // A random volume still yields a plane, but with very few inliers relative to the cloud.
        assertTrue(result == null || result.planeInliers < map.size / 4)
    }

    @Test
    fun colorConversionNamesPrimaryHues() {
        assertEquals("Red", ColorUtil.hueName(ColorUtil.rgbToHsv(255, 0, 0)[0]))
        assertEquals("Green", ColorUtil.hueName(ColorUtil.rgbToHsv(0, 255, 0)[0]))
        assertEquals("Blue", ColorUtil.hueName(ColorUtil.rgbToHsv(0, 0, 255)[0]))
        assertEquals("Yellow", ColorUtil.hueName(ColorUtil.rgbToHsv(255, 255, 0)[0]))
        assertEquals(0f, ColorUtil.rgbToHsv(128, 128, 128)[1], 1e-6f)
    }
}
