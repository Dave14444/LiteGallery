package org.iurl.litegallery

import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * Remembers that a picture was handed to another editing app (e.g. Snapseed), and finds the
 * pictures that app has saved to the phone's gallery meanwhile.
 */
object ExternalEditTracker {

    class Pending(val originalPath: String, val startedAt: Long)

    class NewImage(val path: String, val name: String)

    private const val PREFS = "external_edit"
    private const val KEY_PATH = "original_path"
    private const val KEY_STARTED = "started_at"

    /** A hand-over older than this is forgotten (e.g. the editing app was left open for ages). */
    private const val MAX_AGE_MS = 2L * 60L * 60L * 1000L

    /** Pictures saved a few seconds before the editor started still count (clock differences). */
    private const val TOLERANCE_SECONDS = 5L

    private const val MAX_RESULTS = 10

    fun begin(context: Context, originalPath: String) {
        prefs(context).edit()
            .putString(KEY_PATH, originalPath)
            .putLong(KEY_STARTED, System.currentTimeMillis())
            .apply()
    }

    fun pending(context: Context): Pending? {
        val path = prefs(context).getString(KEY_PATH, null) ?: return null
        val startedAt = prefs(context).getLong(KEY_STARTED, 0L)
        if (startedAt <= 0L || System.currentTimeMillis() - startedAt > MAX_AGE_MS) {
            clear(context)
            return null
        }
        return Pending(path, startedAt)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PATH).remove(KEY_STARTED).apply()
    }

    /** Pictures that were added to the phone's gallery since [sinceMs], newest first. */
    fun findNewImages(context: Context, sinceMs: Long): List<NewImage> {
        val result = mutableListOf<NewImage>()
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA
        )
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
        val arguments = arrayOf(((sinceMs / 1000L) - TOLERANCE_SECONDS).toString())

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arguments,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext() && result.size < MAX_RESULTS) {
                val path = cursor.getString(dataIndex) ?: continue
                val file = File(path)
                if (!file.isFile || file.length() <= 0L) continue
                result.add(NewImage(path, cursor.getString(nameIndex) ?: file.name))
            }
        }
        return result
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
