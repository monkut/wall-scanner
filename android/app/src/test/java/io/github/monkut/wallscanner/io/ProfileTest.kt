package io.github.monkut.wallscanner.io

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    @Test
    fun roundTripsThroughJson() {
        val back = Profile.fromJson(JSONObject(Profile("monkut", 177.8, 228.6).toJson().toString()))
        assertEquals("monkut", back.username)
        assertEquals(177.8, back.heightCm!!, 1e-9)
        assertEquals(228.6, back.reachCm!!, 1e-9)
    }

    @Test
    fun missingLengthsStayUnset() {
        val back = Profile.fromJson(JSONObject(Profile("monkut").toJson().toString()))
        assertNull(back.heightCm)
        assertNull(back.reachCm)
        assertFalse(back.empty)
        assertTrue(Profile.fromJson(JSONObject("{}")).empty)
    }
}
