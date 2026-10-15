package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Copies local media to a network share: asks for the destination folder (starting at the
 * last one used), shows a progress dialog, and reports the result back to the caller.
 */
object SmbUploadFlow {

    private const val PREFS = "smb_file_ops"
    private const val KEY_LAST_FOLDER = "last_upload_folder"

    fun start(
        activity: AppCompatActivity,
        items: List<MediaItemSkeleton>,
        move: Boolean,
        onFinished: (copiedPaths: List<String>, failedCount: Int) -> Unit
    ) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        SmbFolderPicker.show(
            activity,
            prefs.getString(KEY_LAST_FOLDER, null),
            if (move) R.string.smb_move_here else R.string.smb_copy_here
        ) { destFolderUrl ->
            prefs.edit().putString(KEY_LAST_FOLDER, destFolderUrl).apply()
            upload(activity, items, move, destFolderUrl, onFinished)
        }
    }

    private fun upload(
        activity: AppCompatActivity,
        items: List<MediaItemSkeleton>,
        move: Boolean,
        destFolderUrl: String,
        onFinished: (copiedPaths: List<String>, failedCount: Int) -> Unit
    ) {
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle(if (move) R.string.smb_moving else R.string.smb_copying)
            .setMessage("0 / ${items.size}")
            .setCancelable(false)
            .create()
        progressDialog.show()

        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SmbFileOperations.upload(activity.applicationContext, items, destFolderUrl) { done, total ->
                    activity.runOnUiThread { progressDialog.setMessage("$done / $total") }
                }
            }
            progressDialog.dismiss()
            val message = if (result.failed == 0) {
                activity.getString(R.string.smb_operation_done, result.copiedPaths.size)
            } else {
                activity.getString(R.string.smb_operation_partial, result.copiedPaths.size, result.failed)
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            onFinished(result.copiedPaths, result.failed)
        }
    }
}
