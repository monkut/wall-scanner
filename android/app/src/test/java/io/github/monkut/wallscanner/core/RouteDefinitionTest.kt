package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
        for (type in listOf(RouteDefinition.TAPE, RouteDefinition.COMP, RouteDefinition.FREE, RouteDefinition.BOARD)) {
            val result = SceneBuilder.build(SyntheticWall.cloud(), cameras, Random(7), Definition(type))!!
            assertTrue("$type should not auto-group", result.routes.isEmpty())
            assertEquals(9, result.holds.size)
        }
    }

    @Test
    fun sceneJsonCarriesDefinitionRolesAndMembership() {
        val result = SceneBuilder.build(SyntheticWall.cloud(), cameras, Random(7), Definition(RouteDefinition.TAGGED, Rules(FeetRule.OPEN, false)))!!
        result.holds[0].role = HoldRole.START
        val json = SceneJson.write(result, 1, "2026-10-20T15:00:00+09:00", "test")
        assertTrue(json.contains("\"version\": 2"))
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
