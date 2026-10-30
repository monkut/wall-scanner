package io.github.monkut.wallscanner

import android.content.Context
import io.github.monkut.wallscanner.core.UnitSystem

/** App-wide settings other than the language (see [Language]), in the "settings" shared preferences. */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_UNITS = "units"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun units(context: Context): UnitSystem = UnitSystem.fromId(prefs(context).getString(KEY_UNITS, null))

    fun setUnits(context: Context, units: UnitSystem) = prefs(context).edit().putString(KEY_UNITS, units.id).apply()
}
