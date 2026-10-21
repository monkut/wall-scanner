package io.github.monkut.wallscanner

import android.content.Context
import androidx.annotation.PluralsRes
import io.github.monkut.wallscanner.core.GradeSystem
import io.github.monkut.wallscanner.core.RouteDefinition
import io.github.monkut.wallscanner.io.Location
import io.github.monkut.wallscanner.io.LocationKind
import io.github.monkut.wallscanner.io.SendResult
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

    fun definition(c: Context, type: RouteDefinition): String = c.getString(
        when (type) {
            RouteDefinition.MONO_COLOR -> R.string.def_mono_color
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

    /** "Gym, Japanese (Q/D)" for a registered location; empty for the unknown placeholder. */
    fun locationDetail(c: Context, location: Location): String =
        if (location.id.isEmpty()) "" else join(c, kind(c, location.kind), gradeSystem(c, location.gradeSystem))

    fun plural(c: Context, @PluralsRes id: Int, n: Int): String = c.resources.getQuantityString(id, n, n)

    fun join(c: Context, vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString(c.getString(R.string.list_separator))

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
}
