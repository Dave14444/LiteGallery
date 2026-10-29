package org.iurl.litegallery

import android.content.Context
import java.io.File

/**
 * Removes leftovers from the app's cache folder when the app starts: copies made for sharing,
 * the picture that was handed to another app for editing, and the working files of the photo
 * editor. They are normally deleted right after use; they remain if the app was closed or
 * crashed in between.
 *
 * Only files that have not been touched for a while are removed, so a share or an edit that is
 * still going on is not disturbed.
 */
object TempFileCleaner {

    private const val HOUR_MS = 60L * 60L * 1000L

    /** Sub folders of the cache folder and how old their files must be to be removed. */
    internal val RULES: List<Pair<String, Long>> = listOf(
        "share" to HOUR_MS,
        "photo_edit" to HOUR_MS,
        "date_edit" to HOUR_MS,
        // The other app may take long to save its result.
        "external_edit" to 24 * HOUR_MS
    )

    /** Runs [cleanUp] on a background thread. Errors are ignored: this is only housekeeping. */
    fun cleanUpInBackground(context: Context) {
        val cacheDir = context.applicationContext.cacheDir ?: return
        Thread({
            try {
                cleanUp(cacheDir)
            } catch (e: Exception) {
                android.util.Log.w("TempFileCleaner", "Clean-up failed", e)
            }
        }, "temp-file-cleaner").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Deletes the old files in the known sub folders of [cacheDir]. Returns how many were removed. */
    internal fun cleanUp(cacheDir: File, now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        for ((name, maxAgeMs) in RULES) {
            val folder = File(cacheDir, name)
            if (folder.isDirectory) removed += cleanFolder(folder, now - maxAgeMs)
        }
        return removed
    }

    /** Removes files older than [olderThan] below [folder], then empty sub folders (not [folder]). */
    private fun cleanFolder(folder: File, olderThan: Long): Int {
        var removed = 0
        val children = folder.listFiles() ?: return 0
        for (child in children) {
            if (child.isDirectory) {
                removed += cleanFolder(child, olderThan)
                // Only succeeds if the folder is empty now.
                child.delete()
            } else if (child.lastModified() < olderThan && child.delete()) {
                removed++
            }
        }
        return removed
    }
}
