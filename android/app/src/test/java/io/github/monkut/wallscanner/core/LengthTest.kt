package io.github.monkut.wallscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LengthTest {
    @Test
    fun convertsFeetAndInchesBothWays() {
        assertEquals(177.8, Length.fromFeetInches(5, 10), 1e-9)
        assertEquals(5 to 10, Length.feetInches(177.8))
        assertEquals(6 to 0, Length.feetInches(182.0)) // 71.65 in rounds up to 6′ 0″, never 5′ 12″
    }

    @Test
    fun formatsInTheChosenUnits() {
        assertEquals("178 cm", Length.format(177.8, UnitSystem.METRIC))
        assertEquals("5′ 10″", Length.format(177.8, UnitSystem.IMPERIAL))
    }

    @Test
    fun parsesCentimetres() {
        assertEquals(178.0, Length.parseCm(" 178 ")!!, 1e-9)
        assertEquals(177.5, Length.parseCm("177,5")!!, 1e-9)
        assertNull(Length.parseCm(""))
        assertNull(Length.parseCm("tall"))
        assertNull(Length.parseCm("17"))
        assertNull(Length.parseCm("2250"))
    }

    @Test
    fun parsesFeetAndInches() {
        assertEquals(177.8, Length.parseFeetInches("5", "10")!!, 1e-9)
        assertEquals(152.4, Length.parseFeetInches("5", "")!!, 1e-9)
        assertEquals(177.8, Length.parseFeetInches("", "70")!!, 1e-9)
        assertNull(Length.parseFeetInches("", ""))
        assertNull(Length.parseFeetInches("5.5", "0"))
        assertNull(Length.parseFeetInches("-5", "10"))
        assertNull(Length.parseFeetInches("50", "0"))
    }

    @Test
    fun unknownUnitIdFallsBackToMetric() {
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.fromId("imperial"))
        assertEquals(UnitSystem.METRIC, UnitSystem.fromId(null))
        assertEquals(UnitSystem.METRIC, UnitSystem.fromId("furlongs"))
    }
}
