package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class RouteDefinitionTest {
    private val cameras = listOf(SyntheticWall.cameraInFront)

    @Test
    fun monoColorGroupsByColour() {
        val result = SceneBuilder.build(SyntheticWall.cloud(), cameras, Random(7), Definition(RouteDefinition.MONO_COLOR))!!
        assertEquals(3, result.routes.size)
        assertTrue(result.holds.all { it.routeIds.size == 1 && it.routeIds[0] == it.routeId })
    }

    @Test
    fun circuitSplitsColourByPosition() {
        // Red holds (0.5,0.5) (1.5,0.9) (2.5,1.4) chain at ~1.1 m; yellow (0.4,2.2)-(1.2,1.3) are 1.2 m apart but
        // (2.7,0.6) is 1.66 m away; every blue pair is further than 1.4 m apart. So: Red(3), Yellow(2), 4 singletons dropped.
        val result = SceneBuilder.build(SyntheticWall.cloud(), cameras, Random(7), Definition(RouteDefinition.CIRCUIT))!!
        assertEquals(listOf("Red", "Yellow"), result.routes.map { it.name }.sorted())
        assertEquals(3, result.routes.first { it.name == "Red" }.holdIds.size)
        assertEquals(2, result.routes.first { it.name == "Yellow" }.holdIds.size)
        assertEquals(4, result.holds.count { it.routeIds.isEmpty() })
    }

    @Test
    fun tapeAndFreeWallsGetNoAutomaticRoutes() {
        for (type in listOf(RouteDefinition.TAPE, RouteDefinition.COMP, RouteDefinition.FREE, RouteDefinition.BOARD, RouteDefinition.OUTDOOR)) {
            val result = SceneBuilder.build(SyntheticWall.cloud(), cameras, Random(7), Definition(type))!!
            assertTrue("$type should not auto-group", result.routes.isEmpty())
            assertEquals("$type hold count", 9, result.holds.size)
        }
    }

    @Test
    fun outdoorMeasuresHoldsAgainstTheLocalRockSurface() {
        val cloud = SyntheticRock.cloud()
        val result = SceneBuilder.build(cloud, listOf(floatArrayOf(1.5f, 1.2f, 2f)), Random(7), Definition(RouteDefinition.OUTDOOR))!!
        assertTrue("coarse plane should cover the face, inliers=${result.planeInliers}/${cloud.count}", result.planeInliers > cloud.count * 0.9)
        assertEquals("bumps found: ${result.holds.map { "(${it.cx}, ${it.cy}, h=${it.height})" }}", SyntheticRock.bumps.size, result.holds.size)
        for ((bx, by) in SyntheticRock.bumps) {
            assertTrue("no hold near ($bx, $by)", result.holds.any { abs(it.cx - bx) < 0.03f && abs(it.cy - by) < 0.03f })
        }
        for (hold in result.holds) assertTrue("height above local rock ${hold.height}", hold.height in 0.04f..0.08f)
        assertTrue(result.routes.isEmpty())
        assertTrue(result.definition.type.outdoor)
    }

    @Test
    fun gymPipelineMisreadsRock() {
        // The same boulder face through the gym pipeline: holds measured against a flat plane are the rock's
        // own bulges, so the four bumps do not come out as four holds of their own height.
        val cloud = SyntheticRock.cloud()
        val result = SceneBuilder.build(cloud, listOf(floatArrayOf(1.5f, 1.2f, 2f)), Random(7), Definition(RouteDefinition.MONO_COLOR))
        val holds = result?.holds ?: emptyList()
        val bumpsAsHolds = SyntheticRock.bumps.count { (bx, by) ->
            holds.any { abs(it.cx - bx) < 0.03f && abs(it.cy - by) < 0.03f && it.height in 0.04f..0.08f }
        }
        val detail = "inliers=${result?.planeInliers}/${cloud.count} holds=" +
            holds.joinToString { "(%.2f, %.2f, h=%.3f, w=%.2f)".format(it.cx, it.cy, it.height, it.width) }
        assertTrue(detail, bumpsAsHolds < SyntheticRock.bumps.size || holds.size != SyntheticRock.bumps.size)
    }

    @Test
    fun sceneJsonCarriesDefinitionRolesAndMembership() {
        val result = SceneBuilder.build(SyntheticWall.cloud(), cameras, Random(7), Definition(RouteDefinition.TAGGED, Rules(FeetRule.OPEN, false)))!!
        result.holds[0].role = HoldRole.START
        val json = SceneJson.write(result, 1, "2026-10-20T15:00:00+09:00", "test")
        assertTrue(json.contains("\"version\": 4"))
        assertTrue(json.contains("\"confirmed\": false"))
        assertTrue(!json.contains("\"sends\""))
        assertTrue(json.contains("\"definition\": {\"type\": \"tagged\", \"rules\": {\"feet\": \"open\", \"volumesOn\": false}}"))
        assertTrue(json.contains("\"role\": \"start\""))
        assertTrue(json.contains("\"routeIds\": [1]") || json.contains("\"routeIds\": [2]") || json.contains("\"routeIds\": [3]"))
    }

    @Test
    fun idsRoundTrip() {
        for (t in RouteDefinition.entries) assertEquals(t, RouteDefinition.fromId(t.id))
        assertEquals(RouteDefinition.MONO_COLOR, RouteDefinition.fromId("nonsense"))
        assertEquals(HoldRole.ANY, HoldRole.fromId(null))
    }
}
