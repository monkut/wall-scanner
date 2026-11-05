package io.github.monkut.wallscanner.core

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt

/** How the T-nut holes sit on a sheet: rows aligned, or every other row shifted by half a pitch. */
enum class TNutPattern(val id: String) {
    SQUARE("square"), STAGGERED("staggered");

    companion object {
        fun fromId(id: String?): TNutPattern = entries.firstOrNull { it.id == id } ?: SQUARE
    }
}

/** One plywood sheet of the map: its rectangle in wall metres, cut to the wall. */
class Sheet(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

/**
 * A gym wall's map, entered by hand (#8 phase 4): its size, the plywood sheets it is built from and the T-nut
 * lattice on each sheet. Wall coordinates are metres from the wall's bottom-left corner, u across and v up.
 * Sheets tile from that corner, [sheetWidth] × [sheetHeight] as mounted (a 910 × 1820 sheet standing is 0.91
 * wide; lying, 1.82), and the lattice repeats per sheet: the first hole [offsetU] across and [offsetV] up from
 * the sheet's bottom-left corner, then every [pitch]; a [TNutPattern.STAGGERED] sheet shifts every other row by
 * half a pitch. Nothing here is measured from scans: the holes are not extractable from the scan images (#8).
 */
class WallMap(
    val width: Float,
    val height: Float,
    val sheetWidth: Float,
    val sheetHeight: Float,
    val pitch: Float,
    val pattern: TNutPattern,
    val offsetU: Float,
    val offsetV: Float,
) {
    val columns: Int get() = ceil(width / sheetWidth - EPS).toInt()
    val rows: Int get() = ceil(height / sheetHeight - EPS).toInt()

    val valid: Boolean
        get() = width in SIZE_RANGE && height in SIZE_RANGE && sheetWidth > 0f && sheetHeight > 0f &&
            pitch in PITCH_RANGE && offsetU in 0f..pitch && offsetV in 0f..pitch

    /** The sheets row by row from the bottom-left corner, the last column and row cut to the wall. */
    fun sheets(): List<Sheet> = (0 until rows).flatMap { r ->
        (0 until columns).map { c ->
            Sheet(c * sheetWidth, r * sheetHeight, minOf((c + 1) * sheetWidth, width), minOf((r + 1) * sheetHeight, height))
        }
    }

    /** Every T-nut hole on the wall as (u, v), sheet by sheet, bottom row first. */
    fun holes(): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (s in sheets()) {
            var row = 0
            var v = s.v0 + offsetV
            while (v < s.v1 - EPS) {
                var u = s.u0 + offsetU + if (pattern == TNutPattern.STAGGERED && row % 2 == 1) pitch / 2 else 0f
                while (u < s.u1 - EPS) {
                    out.add(floatArrayOf(u, v))
                    u += pitch
                }
                v += pitch
                row++
            }
        }
        return out
    }

    fun toJson(): JSONObject = JSONObject()
        .put("width", mm(width)).put("height", mm(height))
        .put("sheetWidth", mm(sheetWidth)).put("sheetHeight", mm(sheetHeight))
        .put("pitch", mm(pitch)).put("pattern", pattern.id)
        .put("offsetU", mm(offsetU)).put("offsetV", mm(offsetV))

    companion object {
        private const val EPS = 1e-4f
        val SIZE_RANGE = 0.5f..30f
        val PITCH_RANGE = 0.05f..0.5f

        /** Sheet sizes as (short side, long side) in metres: the JIS 3 × 6 shaku sheet and the 4 × 8 ft sheet. */
        val SHEET_JIS = 0.91f to 1.82f
        val SHEET_4X8 = 1.22f to 2.44f

        /** What a new map's form starts from: JIS sheets standing, a 20 cm square grid, first hole 10 cm in. */
        val DEFAULT = WallMap(4f, 4f, SHEET_JIS.first, SHEET_JIS.second, 0.2f, TNutPattern.SQUARE, 0.1f, 0.1f)

        /** Stored to the millimetre. */
        private fun mm(v: Float): Double = (v * 1000).roundToInt() / 1000.0

        fun fromJson(o: JSONObject): WallMap = WallMap(
            o.getDouble("width").toFloat(), o.getDouble("height").toFloat(),
            o.getDouble("sheetWidth").toFloat(), o.getDouble("sheetHeight").toFloat(),
            o.getDouble("pitch").toFloat(), TNutPattern.fromId(o.optString("pattern")),
            o.optDouble("offsetU", 0.0).toFloat(), o.optDouble("offsetV", 0.0).toFloat(),
        )
    }
}
