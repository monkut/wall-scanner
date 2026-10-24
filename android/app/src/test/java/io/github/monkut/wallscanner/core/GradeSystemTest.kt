package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GradeSystemTest {
    @Test
    fun scalesAreOrderedEasyToHardWithoutDuplicates() {
        for (s in GradeSystem.entries) assertEquals("${s.id} has duplicate values", s.values.size, s.values.toSet().size)
        assertEquals(listOf("10Q", "9Q"), GradeSystem.KYU_DAN.values.take(2))
        assertEquals("6D", GradeSystem.KYU_DAN.values.last())
        assertEquals(listOf("VB", "V0"), GradeSystem.V_SCALE.values.take(2))
        assertEquals("V17", GradeSystem.V_SCALE.values.last())
        assertTrue(GradeSystem.FONT.values.containsAll(listOf("6A", "7A+", "8C+")))
        assertTrue(GradeSystem.YDS.values.containsAll(listOf("5.9", "5.10a", "5.15d")))
    }

    @Test
    fun idsRoundTripAndUnknownIsNull() {
        for (s in GradeSystem.entries) assertEquals(s, GradeSystem.fromId(s.id))
        assertNull(GradeSystem.fromId("ewbank"))
        assertNull(GradeSystem.fromId(null))
    }

    @Test
    fun sceneJsonCarriesTheGradeSystem() {
        val result = SceneBuilder.build(SyntheticWall.cloud(), listOf(SyntheticWall.cameraInFront), Random(7))!!
        result.routes[0].grade = "1Q"
        result.routes[0].gradeSystem = GradeSystem.KYU_DAN
        val json = SceneJson.write(result, 1, "2026-10-21T12:00:00+09:00", "test")
        assertTrue(json.contains("\"grade\": \"1Q\", \"gradeSystem\": \"kyu_dan\""))
        assertTrue(json.contains("\"grade\": \"\", \"gradeSystem\": null"))
    }
}
