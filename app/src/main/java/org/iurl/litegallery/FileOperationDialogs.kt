package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress dialog (with a Cancel button) and result message for long-running file operations. */
object FileOperationDialogs {

    // The operation keeps running (and reports back) even if the screen is recreated meanwhile.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** At most this many failed files are listed by name. */
    private const val MAX_LISTED_FAILURES = 25

    /**
     * Runs [work] on a background thread while a progress dialog is shown, then reports the
     * result. [onDone] is only called while [activity] is still alive.
     *
     * The dialog has a Cancel button: [work] then stops after the current file, see
     * [OperationCancel].
     *
     * [progressTextRes] is an optional string with the placeholders %1$d (done) and %2$d (total)
     * instead of "done / total". With [reportResult] false the caller reports the outcome.
     */
    fun runWithProgress(
        activity: AppCompatActivity,
        titleRes: Int,
        total: Int,
        work: (onProgress: (done: Int, total: Int) -> Unit) -> FileTransfer.Result,
        onDone: (FileTransfer.Result) -> Unit,
        progressTextRes: Int = 0,
        reportResult: Boolean = true
    ) {
        val appContext = activity.applicationContext
        OperationCancel.reset()
        fun progressText(done: Int, all: Int): String =
            if (progressTextRes != 0) appContext.getString(progressTextRes, done, all) else "$done / $all"

        val dialog = AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setMessage(progressText(0, total))
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Own click listener: the dialog must stay open until the operation really stopped.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { button ->
            OperationCancel.request()
            button.isEnabled = false
            dialog.setMessage(appContext.getString(R.string.file_op_cancelling))
        }

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                work { done, all ->
                    activity.runOnUiThread {
                        if (dialog.isShowing && !OperationCancel.isRequested()) {
                            dialog.setMessage(progressText(done, all))
                        }
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
            if (reportResult) showResult(appContext, activity.takeIf { alive }, result)
            if (alive) onDone(result)
        }
    }

    /**
     * Shows what happened. If files failed and the screen is still there, they are listed in a
     * dialog; otherwise a short message is enough.
     */
    private fun showResult(context: Context, activity: AppCompatActivity?, result: FileTransfer.Result) {
        val summary = when {
            result.cancelled && result.failed == 0 ->
                context.getString(R.string.file_op_cancelled, result.done)
            result.cancelled ->
                context.getString(R.string.file_op_cancelled_partial, result.done, result.failed)
            result.failed == 0 -> context.getString(R.string.file_op_done, result.done)
            else -> context.getString(R.string.file_op_partial, result.done, result.failed)
        }

        if (activity != null && result.failures.isNotEmpty()) {
            showFailures(activity, summary, result.failures)
        } else {
            Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
        }
    }

    private fun showFailures(
        activity: AppCompatActivity,
        summary: String,
        failures: List<FileTransfer.Failure>
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.file_op_failed_title)
            .setMessage(failureMessage(activity, summary, failures))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** [summary], then one line per failed file (at most [MAX_LISTED_FAILURES]) with the reason. */
    internal fun failureMessage(
        context: Context,
        summary: String,
        failures: List<FileTransfer.Failure>
    ): String {
        val builder = StringBuilder(summary).append("\n")
        failures.take(MAX_LISTED_FAILURES).forEach { failure ->
            val name = Uri.decode(failure.path.trimEnd('/').substringAfterLast('/'))
            builder.append("\n• ").append(name)
                .append("\n   ").append(context.getString(reasonText(failure.reason)))
        }
        val more = failures.size - MAX_LISTED_FAILURES
        if (more > 0) {
            builder.append("\n\n").append(context.getString(R.string.file_op_failed_more, more))
        }
        return builder.toString()
    }

    private fun reasonText(reason: FileTransfer.Reason): Int = when (reason) {
        FileTransfer.Reason.COPY_FAILED -> R.string.file_op_reason_copy
        FileTransfer.Reason.SIZE_MISMATCH -> R.string.file_op_reason_size
        FileTransfer.Reason.DELETE_FAILED -> R.string.file_op_reason_delete
        FileTransfer.Reason.OTHER -> R.string.file_op_reason_other
    }
}
