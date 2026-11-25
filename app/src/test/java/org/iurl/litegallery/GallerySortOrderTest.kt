package org.iurl.litegallery

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GallerySortOrderTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun withoutSettings_newestFirst() {
        assertEquals("date_desc", GallerySortOrder.current(context))
    }

    @Test
    fun followsTheSetting() {
        prefs.edit().putString("default_sort_order", "name_asc").commit()
        assertEquals("name_asc", GallerySortOrder.current(context))
    }

    @Test
    fun theLastChosen_whenRemembered() {
        prefs.edit()
            .putString("default_sort_order", "name_asc")
            .putString("last_folder_sort_order", "size_desc")
            .commit()
        assertEquals("name_asc", GallerySortOrder.current(context))
        prefs.edit().putBoolean("remember_folder_sort_order", true).commit()
        assertEquals("size_desc", GallerySortOrder.current(context))
    }

    @Test
    fun unknownValues_newestFirst() {
        prefs.edit().putString("default_sort_order", "colour").commit()
        assertEquals("date_desc", GallerySortOrder.current(context))
    }
}
