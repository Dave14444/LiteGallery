package org.iurl.litegallery

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * "All files access" (MANAGE_EXTERNAL_STORAGE): lets the app delete, move and create files on
 * the phone without a system confirmation each time.
 */
object StorageAccess {

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Returns true if access is available. Otherwise explains why it is needed, offers to open
     * the system settings page and returns false (the caller should stop).
     */
    fun ensure(activity: Activity): Boolean {
        if (hasAllFilesAccess()) return true

        AlertDialog.Builder(activity)
            .setTitle(R.string.all_files_access_title)
            .setMessage(R.string.all_files_access_message)
            .setPositiveButton(R.string.all_files_access_open_settings) { _, _ ->
                openSettings(activity)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return false
    }

    private fun openSettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
            }
        }
    }
}
