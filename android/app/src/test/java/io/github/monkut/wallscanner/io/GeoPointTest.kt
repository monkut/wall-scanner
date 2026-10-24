package io.github.monkut.wallscanner.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoPointTest {
    @Test
    fun parsesDecimalDegreesWithCommaOrSpace() {
        val a = GeoPoint.parse("35.656718, 139.703624", "t")!!
        assertEquals(35.656718, a.lat, 1e-9)
        assertEquals(139.703624, a.lon, 1e-9)
        assertEquals(GeoPoint.MANUAL_ACCURACY, a.accuracyM)
        val b = GeoPoint.parse("  -33.8688 151.2093 ", "t")!!
        assertEquals(-33.8688, b.lat, 1e-9)
        assertEquals(151.2093, b.lon, 1e-9)
    }

    @Test
    fun rejectsMalformedOrOutOfRangeInput() {
        assertNull(GeoPoint.parse("", "t"))
        assertNull(GeoPoint.parse("35.6", "t"))
        assertNull(GeoPoint.parse("35.6, 139.7, 10", "t"))
        assertNull(GeoPoint.parse("north, east", "t"))
        assertNull(GeoPoint.parse("91, 0", "t"))
        assertNull(GeoPoint.parse("0, 181", "t"))
    }

    @Test
    fun textRoundTripsThroughParse() {
        val p = GeoPoint(35.6567181, 139.7036243, 20.8f, "t")
        val back = GeoPoint.parse(p.text(), "t")!!
        assertEquals(p.lat, back.lat, 1e-6)
        assertEquals(p.lon, back.lon, 1e-6)
        assertEquals("35.656718, 139.703624 (±21 m)", p.toString())
        assertEquals("35.656718, 139.703624", GeoPoint(p.lat, p.lon, GeoPoint.MANUAL_ACCURACY, "t").toString())
    }
}
