package org.iurl.litegallery

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LocaleHelperTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearState()
    }

    @After
    fun tearDown() {
        clearState()
    }

    @Test
    fun getCurrentLanguage_defaultsToSystem() {
        assertEquals(LocaleHelper.LANGUAGE_SYSTEM, LocaleHelper.getCurrentLanguage(context))
    }

    @Test
    fun setLanguage_persistsSelectedLanguage() {
        LocaleHelper.setLanguage(context, LocaleHelper.LANGUAGE_GERMAN)

        assertEquals(LocaleHelper.LANGUAGE_GERMAN, LocaleHelper.getCurrentLanguage(context))
    }

    @Test
    fun getCurrentLanguage_fallsBackToSystemForLanguagesThatWereRemoved() {
        // Chinese was offered by older versions.
        LocaleHelper.setLanguage(context, "zh_TW")
        assertEquals(LocaleHelper.LANGUAGE_SYSTEM, LocaleHelper.getCurrentLanguage(context))

        LocaleHelper.setLanguage(context, "zh_CN")
        assertEquals(LocaleHelper.LANGUAGE_SYSTEM, LocaleHelper.getCurrentLanguage(context))
    }

    @Test
    fun getLanguageDisplayName_mapsKnownAndUnknownLanguageCodes() {
        assertEquals(
            context.getString(R.string.language_english),
            LocaleHelper.getLanguageDisplayName(context, LocaleHelper.LANGUAGE_ENGLISH)
        )
        assertEquals(
            context.getString(R.string.language_german),
            LocaleHelper.getLanguageDisplayName(context, LocaleHelper.LANGUAGE_GERMAN)
        )
        assertEquals(
            context.getString(R.string.language_system_default),
            LocaleHelper.getLanguageDisplayName(context, "unsupported")
        )
    }

    @Test
    fun applyLocale_acceptsAllStoredLanguageValuesWithoutChangingPreference() {
        listOf(
            LocaleHelper.LANGUAGE_SYSTEM,
            LocaleHelper.LANGUAGE_ENGLISH,
            LocaleHelper.LANGUAGE_GERMAN
        ).forEach { language ->
            LocaleHelper.setLanguage(context, language)

            LocaleHelper.applyLocale(context)

            assertEquals(language, LocaleHelper.getCurrentLanguage(context))
        }
    }

    private fun clearState() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .clear()
            .commit()
    }
}
