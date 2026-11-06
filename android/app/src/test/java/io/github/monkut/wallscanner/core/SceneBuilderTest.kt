package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.random.Random

class SceneBuilderTest {
    private val truth = SyntheticWall.holds

    private fun buildCloud(): VoxelCloud = SyntheticWall.cloud()

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
        result.quality = ScanQuality.of(cloud, result.surface, result.holds.size, result.extent)
        val json = SceneJson.write(result, cloud.count, "2026-10-20T10:00:00+09:00", "test")
        assertTrue(json.contains("\"version\": ${SceneJson.VERSION}"))
        assertTrue(json.contains("\"holds\": ["))
        assertTrue(json.contains("\"name\": \"Red\""))
        assertTrue(json.contains("\"kind\": \"route\""))
        assertTrue(json.contains("\"quality\": {\"planeShare\": "))
        assertTrue(json.contains("\"drifted\": false"))
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
