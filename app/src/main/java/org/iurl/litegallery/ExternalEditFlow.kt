package org.iurl.litegallery

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opening a file from a network share in another app (e.g. Snapseed), and what happens after
 * coming back: pictures the other app saved to the phone are offered for moving into the folder
 * "snapseed" next to the original.
 */
object ExternalEditFlow {

    private var isChecking = false

    /** How often the download progress is shown. */
    private const val PROGRESS_REFRESH_MS = 200L

    /** Thrown when the user cancels the download. */
    private class DownloadCancelled : Exception()

    /**
     * Downloads [path] to the app cache and opens the copy with [action] (e.g. ACTION_EDIT or
     * ACTION_VIEW). With [chooser] Android always asks which app to use.
     */
    fun openRemoteInOtherApp(
        activity: AppCompatActivity,
        path: String,
        name: String,
        mimeType: String,
        action: String,
        chooser: Boolean
    ) {
        val source = MediaSourceRegistry.forPath(path) ?: return
        val cancelled = AtomicBoolean(false)

        // "Loading photo… 45 %", a bar, and "12 MB of 27 MB · about 00:08 left": big RAW files
        // take a while over a slow connection.
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
        progressDialog.show()
        ThemeHelper.applyRuntimeCustomColors(progressDialog)
        val startedAt = SystemClock.elapsedRealtime()

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
            val appContext = activity.applicationContext
            val directory = File(activity.cacheDir, "external_edit")
            val file = try {
                withContext(Dispatchers.IO) {
                    directory.deleteRecursively()
                    directory.mkdirs()
                    val target = File(directory, name)
                    // The size from the folder listing, or asked from the share.
                    val total = source.cachedFileSize(path).takeIf { it > 0L } ?: source.fileSize(appContext, path)
                    var done = 0L
                    var shownAt = 0L
                    source.openInput(appContext, path).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                if (cancelled.get()) throw DownloadCancelled()
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
                    val modified = source.lastModified(appContext, path)
                    if (modified > 0L) target.setLastModified(modified)
                    target
                }
            } catch (e: DownloadCancelled) {
                withContext(Dispatchers.IO) { directory.deleteRecursively() }
                return@launch
            } catch (e: Exception) {
                android.util.Log.e("ExternalEditFlow", "Download for editing failed", e)
                null
            }
            progressDialog.dismiss()
            if (cancelled.get()) return@launch

            if (file == null) {
                Toast.makeText(activity, R.string.external_edit_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            try {
                val uri = FileProvider.getUriForFile(activity, "${appContext.packageName}.fileprovider", file)
                val intent = Intent(action).apply {
                    setDataAndType(uri, mimeType)
                    clipData = ClipData.newUri(activity.contentResolver, "media", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                // Remember the hand-over: pictures the other app saves meanwhile are offered later.
                ExternalEditTracker.begin(appContext, path)
                activity.startActivity(if (chooser) Intent.createChooser(intent, name) else intent)
            } catch (e: Exception) {
                android.util.Log.e("ExternalEditFlow", "Could not open $path: ${e.message}")
                ExternalEditTracker.clear(appContext)
                Toast.makeText(activity, R.string.external_edit_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Call when the screen comes back to the front. Looks for pictures another editing app saved
     * to the phone's gallery since the hand-over (for up to about 8 seconds, as Android may list
     * them a moment later) and offers to move them. [onMoved] runs after files were moved.
     */
    fun checkResult(activity: AppCompatActivity, onMoved: () -> Unit) {
        val pending = ExternalEditTracker.pending(activity) ?: return
        if (isChecking) return
        isChecking = true

        activity.lifecycleScope.launch {
            try {
                var found = emptyList<ExternalEditTracker.NewImage>()
                for (attempt in 0 until 4) {
                    found = withContext(Dispatchers.IO) {
                        ExternalEditTracker.findNewImages(activity.applicationContext, pending.startedAt)
                    }
                    if (found.isNotEmpty()) break
                    delay(2000L)
                }
                ExternalEditTracker.clear(activity.applicationContext)
                if (found.isNotEmpty()) showDialog(activity, pending, found, onMoved)
            } finally {
                isChecking = false
            }
        }
    }

    private fun showDialog(
        activity: AppCompatActivity,
        pending: ExternalEditTracker.Pending,
        images: List<ExternalEditTracker.NewImage>,
        onMoved: () -> Unit
    ) {
        val names = images.map { it.name }.toTypedArray()
        val checked = BooleanArray(images.size) { true }

        AlertDialog.Builder(activity)
            .setTitle(R.string.external_edit_found_title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.external_edit_move) { _, _ ->
                val chosen = images.filterIndexed { index, _ -> checked[index] }.map { it.path }
                if (chosen.isNotEmpty()) moveNextToOriginal(activity, pending.originalPath, chosen, onMoved)
            }
            .setNegativeButton(R.string.external_edit_skip, null)
            .show()
    }

    /**
     * Moves [localPaths] from the phone into the folder "snapseed" next to the original on the
     * network share (created if needed). The original is never touched, files with the same name
     * are kept side by side, and the new pictures take over the original's date.
     */
    private fun moveNextToOriginal(
        activity: AppCompatActivity,
        originalPath: String,
        localPaths: List<String>,
        onMoved: () -> Unit
    ) {
        val source = MediaSourceRegistry.forPath(originalPath) ?: return
        // The pictures leave the phone (move): that needs all-files access.
        if (!StorageAccess.ensure(activity)) return

        FileOperationDialogs.runWithProgress(
            activity = activity,
            titleRes = R.string.file_op_moving,
            total = localPaths.size,
            work = { onProgress ->
                val context = activity.applicationContext
                val parent = source.parentFolder(originalPath)
                val destFolder = parent?.let { source.createFolder(context, it, "snapseed") }
                if (destFolder == null) {
                    FileTransfer.Result(0, localPaths.size, emptyList(), emptyList())
                } else {
                    FileTransfer.transfer(
                        context,
                        localPaths,
                        destFolder,
                        FileTransfer.Mode.MOVE,
                        onProgress,
                        source.lastModified(context, originalPath)
                    )
                }
            },
            onDone = { result ->
                if (result.done > 0) {
                    source.parentFolder(originalPath)?.let { FolderMediaRepository.invalidate(it) }
                    onMoved()
                }
            }
        )
    }
}
