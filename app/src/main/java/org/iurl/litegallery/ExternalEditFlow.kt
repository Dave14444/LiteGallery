package org.iurl.litegallery

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opening a file from a network share in another app (e.g. Snapseed), and what happens after
 * coming back: pictures the other app saved to the phone are offered for moving into the folder
 * "snapseed" next to the original.
 */
object ExternalEditFlow {

    private var isChecking = false

    /**
     * Gets a copy of [path] (from the image cache if [cached] has it, else from the share) and
     * opens it with [action] (e.g. ACTION_EDIT or ACTION_VIEW). With [chooser] Android always
     * asks which app to use. [knownModified] is the time of the file as listed (0: unknown).
     */
    fun openRemoteInOtherApp(
        activity: AppCompatActivity,
        path: String,
        name: String,
        mimeType: String,
        action: String,
        chooser: Boolean,
        cached: (() -> File?)? = null,
        knownModified: Long = 0L
    ) {
        val directory = File(activity.cacheDir, "external_edit")
        RemoteCopy.fetch(
            activity,
            path,
            File(directory, name),
            R.string.external_edit_failed,
            cached = cached,
            knownModified = knownModified,
            // One file at a time is handed over: the last one is not needed any more.
            beforeCopy = { directory.deleteRecursively() }
        ) { file ->
            val appContext = activity.applicationContext
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
                AppLock.leavingForOtherApp()
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
     * Where the folder "snapseed" is made for an edited copy of [originalPath]: next to the
     * folder of a RAW file, because RAW files are usually kept in a sub folder of the folder
     * with the JPGs (JPGs/RAW + JPGs/snapseed); next to other pictures in their own folder.
     * At the top of a share it stays in the original's folder. [parentOf] gives a path's folder.
     */
    internal fun snapseedLocation(originalPath: String, parentOf: (String) -> String?): String? {
        val folder = parentOf(originalPath) ?: return null
        if (!RawFileTypes.isRaw(originalPath)) return folder
        return parentOf(folder) ?: folder
    }

    /**
     * Moves [localPaths] from the phone into the folder "snapseed" on the network share (created
     * if needed, see [snapseedLocation]). The original is never touched, files with the same name
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
                val location = snapseedLocation(originalPath) { source.parentFolder(it) }
                val destFolder = location?.let { source.createFolder(context, it, "snapseed") }
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
                    snapseedLocation(originalPath) { source.parentFolder(it) }
                        ?.let { FolderMediaRepository.invalidate(it) }
                    onMoved()
                }
            }
        )
    }
}
