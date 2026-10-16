package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress dialog and result message for long-running file operations. */
object FileOperationDialogs {

    // The operation keeps running (and reports back) even if the screen is recreated meanwhile.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Runs [work] on a background thread while a progress dialog is shown, then reports the
     * result. [onDone] is only called while [activity] is still alive.
     */
    fun runWithProgress(
        activity: AppCompatActivity,
        titleRes: Int,
        total: Int,
        work: (onProgress: (done: Int, total: Int) -> Unit) -> FileTransfer.Result,
        onDone: (FileTransfer.Result) -> Unit
    ) {
        val appContext = activity.applicationContext
        val dialog = AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setMessage("0 / $total")
            .setCancelable(false)
            .create()
        dialog.show()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                work { done, all ->
                    activity.runOnUiThread {
                        if (dialog.isShowing) dialog.setMessage("$done / $all")
                    }
                }
            }
            val alive = !activity.isFinishing && !activity.isDestroyed
            if (alive) {
                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }
            }
            showResult(appContext, result)
            if (alive) onDone(result)
        }
    }

    private fun showResult(context: Context, result: FileTransfer.Result) {
        val message = if (result.failed == 0) {
            context.getString(R.string.file_op_done, result.done)
        } else {
            context.getString(R.string.file_op_partial, result.done, result.failed)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
