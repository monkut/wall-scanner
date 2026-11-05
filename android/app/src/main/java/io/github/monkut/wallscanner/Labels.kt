package io.github.monkut.wallscanner

import android.content.Context
import androidx.annotation.PluralsRes
import io.github.monkut.wallscanner.core.Caption
import io.github.monkut.wallscanner.core.CaptionFont
import io.github.monkut.wallscanner.core.Corner
import io.github.monkut.wallscanner.core.Coverage
import io.github.monkut.wallscanner.core.GradeSystem
import io.github.monkut.wallscanner.core.Length
import io.github.monkut.wallscanner.core.RouteDefinition
import io.github.monkut.wallscanner.core.UnitSystem
import io.github.monkut.wallscanner.core.WallMap
import io.github.monkut.wallscanner.io.Location
import io.github.monkut.wallscanner.io.LocationKind
import io.github.monkut.wallscanner.io.Profile
import io.github.monkut.wallscanner.io.RouteRecord
import io.github.monkut.wallscanner.io.SendResult
import io.github.monkut.wallscanner.io.WallSet
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** User-facing names for the core enums and dates, resolved through the app language (see [Language]). */
object Labels {
    private val JST: ZoneId = ZoneId.of("Asia/Tokyo")
    private val CAPTURE_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** "4.2 × 3.65 m · 910 × 1820 mm sheets · 20 cm grid" (sheet sides as mounted). */
    fun wallMap(c: Context, map: WallMap): String = c.getString(
        R.string.wall_map_summary, metres(map.width), metres(map.height),
        "${millimetres(map.sheetWidth)} × ${millimetres(map.sheetHeight)}", centimetres(map.pitch),
    )

    /** Metres as a plain number for a form field or a summary: "4.2", "3.65". */
    fun metres(m: Float): String = plain(m, 2)

    /** Centimetres from metres: "20", "12.5". */
    fun centimetres(m: Float): String = plain(m * 100, 1)

    private fun millimetres(m: Float): String = plain(m * 1000, 0)

    /** A number without trailing decimal zeros: 4.20 → "4.2", 20.0 → "20", 910 → "910". */
    private fun plain(v: Float, decimals: Int): String {
        val text = String.format(Locale.US, "%.${decimals}f", v)
        return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    }

    fun definition(c: Context, type: RouteDefinition): String = c.getString(
        when (type) {
            RouteDefinition.MONO_COLOR -> R.string.def_mono_color
            RouteDefinition.COLOR_TAPED -> R.string.def_color_taped
            RouteDefinition.TAPE -> R.string.def_tape
            RouteDefinition.CIRCUIT -> R.string.def_circuit
            RouteDefinition.TAGGED -> R.string.def_tagged
            RouteDefinition.COMP -> R.string.def_comp
            RouteDefinition.FREE -> R.string.def_free
            RouteDefinition.BOARD -> R.string.def_board
            RouteDefinition.OUTDOOR -> R.string.def_outdoor
        }
    )

    fun definitionDescription(c: Context, type: RouteDefinition): String = c.getString(
        when (type) {
            RouteDefinition.MONO_COLOR -> R.string.def_mono_color_desc
            RouteDefinition.COLOR_TAPED -> R.string.def_color_taped_desc
            RouteDefinition.TAPE -> R.string.def_tape_desc
            RouteDefinition.CIRCUIT -> R.string.def_circuit_desc
            RouteDefinition.TAGGED -> R.string.def_tagged_desc
            RouteDefinition.COMP -> R.string.def_comp_desc
            RouteDefinition.FREE -> R.string.def_free_desc
            RouteDefinition.BOARD -> R.string.def_board_desc
            RouteDefinition.OUTDOOR -> R.string.def_outdoor_desc
        }
    )

    fun gradeSystem(c: Context, system: GradeSystem): String = c.getString(
        when (system) {
            GradeSystem.YDS -> R.string.grade_system_yds
            GradeSystem.KYU_DAN -> R.string.grade_system_kyu_dan
            GradeSystem.V_SCALE -> R.string.grade_system_v
            GradeSystem.FONT -> R.string.grade_system_font
        }
    )

    fun kind(c: Context, kind: LocationKind): String =
        c.getString(if (kind == LocationKind.OUTDOOR) R.string.kind_outdoor else R.string.kind_gym)

    fun result(c: Context, result: SendResult): String = c.getString(
        when (result) {
            SendResult.FLASH -> R.string.result_flash
            SendResult.SEND -> R.string.result_send
            SendResult.ATTEMPT -> R.string.result_attempt
        }
    )

    fun location(c: Context, location: Location): String =
        if (location.id.isEmpty()) c.getString(R.string.unknown_location) else location.name

    /** "October Set", or "October Set (retired)" once the wall was reset. */
    fun set(c: Context, set: WallSet): String = if (set.active) set.name else c.getString(R.string.set_retired_label, set.name)

    /** "October Set" / "10月セット" for the current month (JST) in the app language. */
    fun defaultSetName(c: Context): String =
        c.getString(R.string.default_set_name, ZonedDateTime.now(JST).format(DateTimeFormatter.ofPattern("LLLL", locale(c))))

    /** "Gym, Japanese (Q/D)" for a registered location; empty for the unknown placeholder. */
    fun locationDetail(c: Context, location: Location): String =
        if (location.id.isEmpty()) "" else join(
            c, kind(c, location.kind), gradeSystem(c, location.gradeSystem),
            if (location.coords != null) c.getString(R.string.location_has_coords) else "",
        )

    fun plural(c: Context, @PluralsRes id: Int, n: Int): String = c.resources.getQuantityString(id, n, n)

    fun join(c: Context, vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString(c.getString(R.string.list_separator))

    fun units(c: Context, units: UnitSystem): String = c.getString(
        when (units) {
            UnitSystem.METRIC -> R.string.units_metric
            UnitSystem.IMPERIAL -> R.string.units_imperial
        }
    )

    /** "My face, bystanders' faces" for the face masks that are on; "Off" when neither is. */
    fun faceMasks(c: Context, climber: Boolean, bystanders: Boolean): String =
        join(c, if (climber) c.getString(R.string.face_mask_climber) else "", if (bystanders) c.getString(R.string.face_mask_bystanders) else "")
            .ifEmpty { c.getString(R.string.setting_off) }

    fun corner(c: Context, corner: Corner): String = c.getString(
        when (corner) {
            Corner.UPPER_LEFT -> R.string.corner_upper_left
            Corner.UPPER_RIGHT -> R.string.corner_upper_right
            Corner.LOWER_LEFT -> R.string.corner_lower_left
            Corner.LOWER_RIGHT -> R.string.corner_lower_right
        }
    )

    fun captionFont(c: Context, font: CaptionFont): String = c.getString(
        when (font) {
            CaptionFont.ATKINSON -> R.string.font_atkinson
            CaptionFont.SANS -> R.string.font_sans
            CaptionFont.CONDENSED -> R.string.font_condensed
            CaptionFont.SERIF -> R.string.font_serif
            CaptionFont.MONO -> R.string.font_mono
            CaptionFont.CASUAL -> R.string.font_casual
            CaptionFont.CURSIVE -> R.string.font_cursive
        }
    )

    /** "Lower left, Atkinson Hyperlegible" for the caption setting; "Off" when it is off. */
    fun captionSetting(c: Context, on: Boolean, corner: Corner, font: CaptionFont): String =
        if (!on) c.getString(R.string.setting_off) else join(c, corner(c, corner), captionFont(c, font))

    /** The caption for a climb on [route]: "Red V3" over "noborock shibuya, Main wall, November Set" (parts left out when unset). */
    fun caption(c: Context, route: RouteRecord?, location: Location): Caption {
        val title = if (route == null) "" else listOf(route.name, route.grade).filter { it.isNotBlank() }.joinToString(" ")
        val detail = join(
            c, if (location.id.isEmpty()) "" else location.name,
            location.wall(route?.wallId)?.name ?: "", location.set(route?.setId)?.name ?: "",
        )
        return Caption(title, detail)
    }

    /** "Height 178 cm, reach 225 cm" with the lengths in [units]; "" when neither is set. */
    fun profileDetail(c: Context, profile: Profile, units: UnitSystem): String =
        join(
            c,
            profile.heightCm?.let { c.getString(R.string.profile_height_value, Length.format(it, units)) } ?: "",
            profile.reachCm?.let { c.getString(R.string.profile_reach_value, Length.format(it, units)) } ?: "",
        ).replaceFirstChar { it.titlecase() }

    /** "top right" / "右上": a cell of the depth-coverage grid (row 0 = top, col 0 = left). */
    fun coverageCell(c: Context, cell: Coverage.Cell): String {
        val row = c.getString(when (cell.row) { 0 -> R.string.row_top; 1 -> R.string.row_middle; else -> R.string.row_bottom })
        val col = c.getString(when (cell.col) { 0 -> R.string.col_left; 1 -> R.string.col_centre; else -> R.string.col_right })
        return c.getString(R.string.coverage_cell, row, col)
    }

    private fun locale(c: Context): Locale = c.resources.configuration.locales[0] ?: Locale.US

    private fun dateTime(c: Context): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale(c))

    /** Capture directory name (JST `yyyyMMdd-HHmmss`, optional `-n` suffix) as a localized date and time. */
    fun captureDate(c: Context, captureId: String): String = runCatching {
        LocalDateTime.parse(captureId.take(15), CAPTURE_NAME).format(dateTime(c))
    }.getOrDefault(captureId)

    /** Capture directory name as a localized date only, for references inside other rows. */
    fun captureDay(c: Context, captureId: String): String = runCatching {
        LocalDateTime.parse(captureId.take(15), CAPTURE_NAME).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale(c)))
    }.getOrDefault(captureId)

    /** ISO offset timestamp as a localized JST date and time. */
    fun isoDateTime(c: Context, iso: String): String = runCatching {
        ZonedDateTime.parse(iso).withZoneSameInstant(JST).format(dateTime(c))
    }.getOrDefault(iso)

    fun isoDate(c: Context, iso: String): String = runCatching {
        ZonedDateTime.parse(iso).withZoneSameInstant(JST).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale(c)))
    }.getOrDefault(iso.take(10))

    /** A clip's length as m:ss. */
    fun duration(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)
}
