package org.iurl.litegallery

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * The order the folder screen shows pictures in (setting "Sort order", or the one last chosen if
 * it is to be remembered): "date_desc", "date_asc", "name_asc", "name_desc", "size_desc" or
 * "size_asc". Other lists of files (e.g. the RAW files in the network browser) follow it.
 */
object GallerySortOrder {

    private const val PREF_DEFAULT = "default_sort_order"
    private const val PREF_REMEMBER = "remember_folder_sort_order"
    private const val PREF_LAST = "last_folder_sort_order"
    private val VALUES = setOf("date_desc", "date_asc", "name_asc", "name_desc", "size_desc", "size_asc")

    fun current(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val default = parse(prefs.getString(PREF_DEFAULT, null))
        return if (prefs.getBoolean(PREF_REMEMBER, false)) parse(prefs.getString(PREF_LAST, default)) else default
    }

    fun parse(value: String?): String = if (value in VALUES) value!! else "date_desc"
}
