package org.iurl.litegallery

import android.app.AlertDialog
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A file from a network share as a local copy, e.g. to share it or to open it in another app.
 *
 * A picture that was shown already is copied from the image cache at once (see RemoteOriginals).
 * Anything else is read from the share; if that takes a moment, "Loading picture… 45 %" with a
 * bar, the time left and Cancel is shown.
 */
object RemoteCopy {

    /** The progress is shown only if getting the copy takes longer than this. */
    private const val SHOW_PROGRESS_AFTER_MS = 300L

    /** How often the progress is updated. */
    private const val PROGRESS_REFRESH_MS = 200L

    /** Thrown when the user cancels. */
    private class Cancelled : Exception()

    /**
     * Copies [path] to [target] and then calls [onReady] with it (on the main thread).
     *
     * [cached] gives the original from the image cache if it is there. [knownModified] is the
     * time of the file as listed (0: asked from the share); the copy gets it. [beforeCopy] runs
     * first, in the background (e.g. to clear old copies). If it fails, [failedMessage] is shown.
     */
    fun fetch(
        activity: AppCompatActivity,
        path: String,
        target: File,
        @StringRes failedMessage: Int,
        cached: (() -> File?)? = null,
        knownModified: Long = 0L,
        beforeCopy: () -> Unit = {},
        onReady: (File) -> Unit
    ) {
        val source = MediaSourceRegistry.forPath(path)
        if (source == null) {
            Toast.makeText(activity, failedMessage, Toast.LENGTH_LONG).show()
            return
        }
        val cancelled = AtomicBoolean(false)

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 1000
        }
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val barContainer = FrameLayout(activity).apply {
            setPadding(padding, padding / 3, padding, 0)
            addView(bar)
        }
        val progressDialog = AlertDialog.Builder(activity)
            .setMessage(R.string.external_edit_downloading)
            .setView(barContainer)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled.set(true) }
            .create()
        var startedAt = SystemClock.elapsedRealtime()

        fun showProgress(done: Long, total: Long) {
            if (!progressDialog.isShowing) return
            val loaded = Formatter.formatShortFileSize(activity, done)
            if (total <= 0L) {
                progressDialog.setMessage(
                    activity.getString(R.string.external_edit_downloading) + "\n" +
                        activity.getString(R.string.viewer_loading_bytes, loaded)
                )
                return
            }
            val permille = (done * 1000L / total).coerceIn(0L, 1000L).toInt()
            bar.isIndeterminate = false
            bar.progress = permille
            var details = activity.getString(
                R.string.file_op_bytes_progress, loaded, Formatter.formatShortFileSize(activity, total)
            )
            OperationProgress.estimateSecondsLeft(done, total, SystemClock.elapsedRealtime() - startedAt)
                ?.let { details += " · " + activity.getString(R.string.file_op_time_left, DateUtils.formatElapsedTime(it)) }
            progressDialog.setMessage(
                activity.getString(R.string.external_edit_downloading_percent, permille / 10) + "\n" + details
            )
        }

        activity.lifecycleScope.launch {
            // From the cache it is ready at once: no dialog flashing up.
            val showDialog = launch {
                delay(SHOW_PROGRESS_AFTER_MS)
                progressDialog.show()
                ThemeHelper.applyRuntimeCustomColors(progressDialog)
            }
            val appContext = activity.applicationContext
            val file = try {
                withContext(Dispatchers.IO) {
                    beforeCopy()
                    target.parentFile?.mkdirs()
                    val fromCache = cached?.invoke()
                    if (fromCache != null) {
                        fromCache.copyTo(target, overwrite = true)
                    } else {
                        startedAt = SystemClock.elapsedRealtime()
                        // The size from the folder listing, or asked from the share.
                        val total = source.cachedFileSize(path).takeIf { it > 0L } ?: source.fileSize(appContext, path)
                        var done = 0L
                        var shownAt = 0L
                        source.openInput(appContext, path).use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    if (cancelled.get()) throw Cancelled()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    done += count
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - shownAt >= PROGRESS_REFRESH_MS) {
                                        shownAt = now
                                        val snapshot = done
                                        activity.runOnUiThread { showProgress(snapshot, total) }
                                    }
                                }
                            }
                        }
                    }
                    val modified = knownModified.takeIf { it > 0L } ?: source.lastModified(appContext, path)
                    if (modified > 0L) target.setLastModified(modified)
                    target
                }
            } catch (e: Cancelled) {
                withContext(Dispatchers.IO) { target.delete() }
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The screen was closed meanwhile.
                target.delete()
                throw e
            } catch (e: Exception) {
                android.util.Log.e("RemoteCopy", "Copy of $path failed", e)
                withContext(Dispatchers.IO) { target.delete() }
                if (!cancelled.get()) Toast.makeText(activity, failedMessage, Toast.LENGTH_LONG).show()
                null
            } finally {
                showDialog.cancel()
                progressDialog.dismiss()
            }
            if (file != null && !cancelled.get()) onReady(file)
        }
    }
}
