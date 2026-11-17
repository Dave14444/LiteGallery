package org.iurl.litegallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.iurl.litegallery.MediaAdapter.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FolderViewModeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        FolderViewMode.resetForTest()
    }

    private fun settings(default: String? = null, remember: Boolean = false, last: String? = null) {
        prefs.edit().apply {
            default?.let { putString("default_view_mode", it) }
            putBoolean("remember_folder_view_mode", remember)
            last?.let { putString("last_folder_view_mode", it) }
        }.commit()
    }

    private fun handedOver(mode: ViewMode) =
        Intent().putExtra(FolderViewMode.EXTRA_VIEW_MODE, FolderViewMode.value(mode))

    private fun rebuilt(mode: ViewMode, choicesSeen: Int = 0) =
        Bundle().also { FolderViewMode.save(it, mode, choicesSeen) }

    @Test
    fun withoutAnything_theThumbnailsAreShown() {
        assertEquals(ViewMode.GRID, FolderViewMode.initial(context, null, null))
    }

    @Test
    fun withoutRemembering_theDefaultIsUsed() {
        settings(default = "detailed", remember = false, last = "list")
        assertEquals(ViewMode.DETAILED, FolderViewMode.initial(context, null, Intent()))
    }

    @Test
    fun withRemembering_theLastChosenViewIsUsed() {
        settings(default = "detailed", remember = true, last = "list")
        assertEquals(ViewMode.LIST, FolderViewMode.initial(context, null, Intent()))
    }

    @Test
    fun withRememberingButNothingChosenYet_theDefaultIsUsed() {
        settings(default = "large_grid", remember = true)
        assertEquals(ViewMode.LARGE_GRID, FolderViewMode.initial(context, null, null))
    }

    @Test
    fun aSubFolder_opensInTheViewOfTheFolderAboveIt() {
        settings(default = "grid", remember = false)
        assertEquals(ViewMode.DETAILED, FolderViewMode.initial(context, null, handedOver(ViewMode.DETAILED)))
    }

    @Test
    fun turningTheDevice_keepsTheChosenView() {
        settings(default = "grid", remember = false)
        // Opened with the list view, then switched to large tiles, then turned.
        val view = FolderViewMode.initial(context, rebuilt(ViewMode.LARGE_GRID), handedOver(ViewMode.LIST))
        assertEquals(ViewMode.LARGE_GRID, view)
    }

    @Test
    fun choosingAView_isRememberedOnlyWhenSwitchedOn() {
        settings(remember = false)
        FolderViewMode.choose(context, ViewMode.LIST)
        assertNull(prefs.getString("last_folder_view_mode", null))

        settings(remember = true)
        FolderViewMode.choose(context, ViewMode.DETAILED)
        assertEquals("detailed", prefs.getString("last_folder_view_mode", null))
    }

    @Test
    fun aFolderFurtherUp_takesOverTheViewChosenInASubFolder() {
        val seenByParent = FolderViewMode.choicesSeen(null)
        assertNull(FolderViewMode.chosenSince(seenByParent))

        // In the sub folder: list, then details.
        FolderViewMode.choose(context, ViewMode.LIST)
        FolderViewMode.choose(context, ViewMode.DETAILED)

        assertEquals(ViewMode.DETAILED, FolderViewMode.chosenSince(seenByParent))
        // Taken over: nothing new afterwards.
        assertNull(FolderViewMode.chosenSince(FolderViewMode.choices))
    }

    @Test
    fun aFolderFurtherUpThatWasRebuilt_stillTakesOverTheView() {
        val seenByParent = FolderViewMode.choicesSeen(null)
        // The folder further up is rebuilt while in the background (e.g. the device was turned).
        val state = rebuilt(ViewMode.GRID, seenByParent)
        FolderViewMode.choose(context, ViewMode.LIST)

        assertEquals(ViewMode.LIST, FolderViewMode.chosenSince(FolderViewMode.choicesSeen(state)))
    }

    @Test
    fun theSwitch_goesThroughAllViewsAndBack() {
        var mode = ViewMode.GRID
        val seen = mutableListOf(mode)
        repeat(4) {
            mode = FolderViewMode.next(mode)
            seen += mode
        }
        assertEquals(
            listOf(ViewMode.GRID, ViewMode.LARGE_GRID, ViewMode.LIST, ViewMode.DETAILED, ViewMode.GRID),
            seen
        )
    }

    @Test
    fun valuesOfTheSettings_areReadBack() {
        ViewMode.entries.forEach { mode ->
            assertEquals(mode, FolderViewMode.parse(FolderViewMode.value(mode)))
        }
        assertNull(FolderViewMode.parse("something else"))
        assertEquals(listOf(3, 2, 1, 1), ViewMode.entries.map { FolderViewMode.columns(it) })
    }
}
