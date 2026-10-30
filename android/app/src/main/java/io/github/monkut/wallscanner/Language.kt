package io.github.monkut.wallscanner

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * App language, independent of the device language. Stored by AppCompat (per-app locales on Android 13+,
 * `AppLocalesMetadataHolderService` below that) and applied to every activity and the viewer page.
 */
enum class Language(val tag: String, @StringRes val label: Int) {
    EN_US("en-US", R.string.language_en_us),
    EN_GB("en-GB", R.string.language_en_gb),
    JA("ja", R.string.language_ja);

    companion object {
        val DEFAULT = EN_US

        fun current(): Language {
            val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            return entries.firstOrNull { tags.startsWith(it.tag, ignoreCase = true) } ?: DEFAULT
        }

        fun apply(language: Language) = AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))

        /** First launch: the app starts in English (US) whatever the device language. */
        fun ensureChosen() {
            if (AppCompatDelegate.getApplicationLocales().isEmpty) apply(DEFAULT)
        }
    }
}
