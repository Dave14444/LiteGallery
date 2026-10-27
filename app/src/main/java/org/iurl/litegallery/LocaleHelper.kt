package org.iurl.litegallery

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager

object LocaleHelper {

    private const val LANGUAGE_PREFERENCE_KEY = "app_language"

    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_GERMAN = "de"

    /**
     * Apply the saved locale preference to the app
     */
    fun applyLocale(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val language = getCurrentLanguage(context)

        if (language == LANGUAGE_SYSTEM) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            val locale = getLocaleFromLanguageCode(language)
            AppCompatDelegate.setApplicationLocales(locale)
        }
    }

    /**
     * Get the current language preference
     */
    fun getCurrentLanguage(context: Context): String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val saved = preferences.getString(LANGUAGE_PREFERENCE_KEY, LANGUAGE_SYSTEM)
        // Only English and German are offered. A language chosen in an older version (e.g.
        // Chinese) falls back to the system language.
        return if (saved == LANGUAGE_ENGLISH || saved == LANGUAGE_GERMAN) saved else LANGUAGE_SYSTEM
    }

    /**
     * Set a new language preference
     */
    fun setLanguage(context: Context, language: String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit()
            .putString(LANGUAGE_PREFERENCE_KEY, language)
            .apply()
    }

    /**
     * Get display name for a language code
     */
    fun getLanguageDisplayName(context: Context, language: String): String {
        return when (language) {
            LANGUAGE_ENGLISH -> context.getString(R.string.language_english)
            LANGUAGE_GERMAN -> context.getString(R.string.language_german)
            else -> context.getString(R.string.language_system_default)
        }
    }

    /**
     * Convert app language code to LocaleListCompat
     */
    private fun getLocaleFromLanguageCode(language: String): LocaleListCompat {
        return when (language) {
            LANGUAGE_ENGLISH -> LocaleListCompat.forLanguageTags("en")
            LANGUAGE_GERMAN -> LocaleListCompat.forLanguageTags("de") 
            else -> LocaleListCompat.getEmptyLocaleList()
        }
    }
}
