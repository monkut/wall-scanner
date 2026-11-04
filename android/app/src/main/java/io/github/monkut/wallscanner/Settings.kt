package io.github.monkut.wallscanner

import android.content.Context
import io.github.monkut.wallscanner.core.CaptionFont
import io.github.monkut.wallscanner.core.Corner
import io.github.monkut.wallscanner.core.UnitSystem

/** App-wide settings other than the language (see [Language]), in the "settings" shared preferences. */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_UNITS = "units"
    private const val KEY_MASK_CLIMBER = "maskClimberFace"
    private const val KEY_MASK_BYSTANDERS = "maskBystanderFaces"
    private const val KEY_CAPTION = "caption"
    private const val KEY_CAPTION_CORNER = "captionCorner"
    private const val KEY_CAPTION_FONT = "captionFont"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun units(context: Context): UnitSystem = UnitSystem.fromId(prefs(context).getString(KEY_UNITS, null))

    fun setUnits(context: Context, units: UnitSystem) = prefs(context).edit().putString(KEY_UNITS, units.id).apply()

    /** Face masks (issue #12), both on unless turned off: hide the climber's face, and every other face, in climb video playback and shared clips. */
    fun maskClimber(context: Context): Boolean = prefs(context).getBoolean(KEY_MASK_CLIMBER, true)

    fun maskBystanders(context: Context): Boolean = prefs(context).getBoolean(KEY_MASK_BYSTANDERS, true)

    /** Whether anything is masked, so filming knows whether to look for faces at all. */
    fun masksOn(context: Context): Boolean = maskClimber(context) || maskBystanders(context)

    fun setFaceMasks(context: Context, climber: Boolean, bystanders: Boolean) =
        prefs(context).edit().putBoolean(KEY_MASK_CLIMBER, climber).putBoolean(KEY_MASK_BYSTANDERS, bystanders).apply()

    /** Caption on climb videos (issue #13): gym, set and route in a corner, on by default; lower left in the app font unless changed. */
    fun caption(context: Context): Boolean = prefs(context).getBoolean(KEY_CAPTION, true)

    fun captionCorner(context: Context): Corner = Corner.fromId(prefs(context).getString(KEY_CAPTION_CORNER, null))

    fun captionFont(context: Context): CaptionFont = CaptionFont.fromId(prefs(context).getString(KEY_CAPTION_FONT, null))

    fun setCaption(context: Context, on: Boolean, corner: Corner, font: CaptionFont) =
        prefs(context).edit().putBoolean(KEY_CAPTION, on).putString(KEY_CAPTION_CORNER, corner.id).putString(KEY_CAPTION_FONT, font.id).apply()
}
