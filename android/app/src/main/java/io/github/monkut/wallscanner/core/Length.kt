package io.github.monkut.wallscanner.core

import kotlin.math.roundToInt

/** How body lengths (height, reach) are shown and entered; an app setting. Stored values are always centimetres. */
enum class UnitSystem(val id: String) {
    METRIC("metric"), IMPERIAL("imperial");

    companion object {
        val DEFAULT = METRIC

        fun fromId(id: String?): UnitSystem = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Body lengths in centimetres: conversion to and from feet and inches, display text and input parsing. */
object Length {
    const val CM_PER_INCH = 2.54

    /** Accepted input: anything outside is a typo (a height of 17 cm, a reach of 2250 cm). */
    val RANGE_CM = 50.0..300.0

    /** Whole feet and inches, rounded to the nearest inch: 177.8 cm is (5, 10). */
    fun feetInches(cm: Double): Pair<Int, Int> {
        val inches = (cm / CM_PER_INCH).roundToInt()
        return inches / 12 to inches % 12
    }

    fun fromFeetInches(feet: Int, inches: Int): Double = (feet * 12 + inches) * CM_PER_INCH

    /** "178 cm" or "5′ 10″". */
    fun format(cm: Double, units: UnitSystem): String = when (units) {
        UnitSystem.METRIC -> "${cm.roundToInt()} cm"
        UnitSystem.IMPERIAL -> feetInches(cm).let { (feet, inches) -> "$feet′ $inches″" }
    }

    /** Centimetres from a cm field ("178", "177.5", "177,5"); null when not a number or outside [RANGE_CM]. */
    fun parseCm(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in RANGE_CM }

    /** Centimetres from feet and inches fields (a blank one counts as 0); null when not whole numbers or outside [RANGE_CM]. */
    fun parseFeetInches(feet: String, inches: String): Double? {
        val f = feet.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val i = inches.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return fromFeetInches(f, i).takeIf { it in RANGE_CM }
    }
}
