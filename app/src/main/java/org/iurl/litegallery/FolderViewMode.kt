package org.iurl.litegallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.preference.PreferenceManager
import org.iurl.litegallery.MediaAdapter.ViewMode

/**
 * The view of the folder screens (thumbnails, large tiles, list, details), for pictures and
 * folders alike.
 *
 * What is chosen in one folder screen holds for the whole way through the folders: a sub folder
 * opens with it, and the folders further up take it over when coming back to them. Turning the
 * device (or any other rebuild of a screen by Android) keeps it. With the setting "remember the
 * view" it also counts for the next folder opened from the home screen; otherwise such a folder
 * starts with the default view.
 */
object FolderViewMode {

    /** The view handed over to the next folder screen, and kept when a screen is rebuilt. */
    const val EXTRA_VIEW_MODE = "org.iurl.litegallery.extra.VIEW_MODE"

    private const val STATE_CHOICES_SEEN = "org.iurl.litegallery.state.VIEW_MODE_CHOICES_SEEN"

    private const val PREF_DEFAULT = "default_view_mode"
    private const val PREF_REMEMBER = "remember_folder_view_mode"
    private const val PREF_LAST = "last_folder_view_mode"

    /** How many times a view was chosen since the app started; screens compare it when they come back. */
    @Volatile
    var choices = 0
        private set

    @Volatile
    private var lastChoice: ViewMode? = null

    /** The view a folder screen starts with: kept from before, handed over, or from the settings. */
    fun initial(context: Context, savedState: Bundle?, intent: Intent?): ViewMode =
        parse(savedState?.getString(EXTRA_VIEW_MODE))
            ?: parse(intent?.getStringExtra(EXTRA_VIEW_MODE))
            ?: fromSettings(context)

    /** The view from the settings: the last chosen one if it is to be remembered, else the default. */
    fun fromSettings(context: Context): ViewMode {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val default = parse(prefs.getString(PREF_DEFAULT, null)) ?: ViewMode.GRID
        if (!prefs.getBoolean(PREF_REMEMBER, false)) return default
        return parse(prefs.getString(PREF_LAST, null)) ?: default
    }

    /** The choices a screen had seen before it was rebuilt (or all so far for a new screen). */
    fun choicesSeen(savedState: Bundle?): Int = savedState?.getInt(STATE_CHOICES_SEEN, choices) ?: choices

    fun save(outState: Bundle, mode: ViewMode, choicesSeen: Int) {
        outState.putString(EXTRA_VIEW_MODE, value(mode))
        outState.putInt(STATE_CHOICES_SEEN, choicesSeen)
    }

    /** [mode] was chosen in a folder screen. */
    fun choose(context: Context, mode: ViewMode) {
        lastChoice = mode
        choices++
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_REMEMBER, false)) {
            prefs.edit().putString(PREF_LAST, value(mode)).apply()
        }
    }

    /** The view chosen in another screen since a screen saw [choicesSeen] choices, or null. */
    fun chosenSince(choicesSeen: Int): ViewMode? = if (choicesSeen != choices) lastChoice else null

    /** The next view of the switch in the toolbar. */
    fun next(mode: ViewMode): ViewMode = when (mode) {
        ViewMode.GRID -> ViewMode.LARGE_GRID
        ViewMode.LARGE_GRID -> ViewMode.LIST
        ViewMode.LIST -> ViewMode.DETAILED
        ViewMode.DETAILED -> ViewMode.GRID
    }

    fun label(context: Context, mode: ViewMode): String = context.getString(
        when (mode) {
            ViewMode.GRID -> R.string.thumbnail_view
            ViewMode.LARGE_GRID -> R.string.large_thumbnail_view
            ViewMode.LIST -> R.string.list_view
            ViewMode.DETAILED -> R.string.detailed_view
        }
    )

    /** Columns of a grid view; 1 for the list views. */
    fun columns(mode: ViewMode): Int = when (mode) {
        ViewMode.GRID -> 3
        ViewMode.LARGE_GRID -> 2
        ViewMode.LIST, ViewMode.DETAILED -> 1
    }

    /** The value in the settings (see view_mode_values). */
    fun value(mode: ViewMode): String = when (mode) {
        ViewMode.GRID -> "grid"
        ViewMode.LARGE_GRID -> "large_grid"
        ViewMode.LIST -> "list"
        ViewMode.DETAILED -> "detailed"
    }

    fun parse(value: String?): ViewMode? = when (value) {
        "grid" -> ViewMode.GRID
        "large_grid" -> ViewMode.LARGE_GRID
        "list" -> ViewMode.LIST
        "detailed" -> ViewMode.DETAILED
        else -> null
    }

    /** For tests: as after a fresh start of the app. */
    internal fun resetForTest() {
        choices = 0
        lastChoice = null
    }
}
