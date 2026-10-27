package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException

/**
 * Removes previews from the thumbnail folder of a share that no picture needs any more: those of
 * deleted, renamed or changed files, and those made by older versions of the app. The whole share
 * is searched first and the user is told how much would go before anything is deleted.
 */
object ThumbnailCleanup {

    /** The unused files in the thumbnail folder of [shareRoot]. */
    class Scan(val shareRoot: String, val unused: List<RemoteFileInfo>) {
        val totalBytes: Long get() = unused.sumOf { it.size.coerceAtLeast(0L) }
    }

    /**
     * Searches the share of [anyUrlOnShare] for unused previews. Returns null if the user
     * cancelled. Throws if a folder cannot be read: then it is not known which previews are in
     * use, so nothing may be deleted.
     */
    fun scan(context: Context, anyUrlOnShare: String, onProgress: (foldersSearched: Int) -> Unit): Scan? {
        val root = SmbTrash.shareRootOf(anyUrlOnShare) ?: throw IOException("Not an SMB share")
        val smb = SmbPath.parse(root) ?: throw IOException("Not an SMB share")
        val trashUrl = SmbPath.toUrl(smb.host, smb.share, SmbTrash.TRASH_DIR)

        val validKeys = HashSet<String>()
        // The trash folder is hidden, but its previews are shown too: look into it as well.
        val queue = ArrayDeque<String>().apply {
            add(root)
            add(trashUrl)
        }
        var searched = 0

        while (queue.isNotEmpty()) {
            if (OperationCancel.isRequested()) return null
            val folder = queue.removeFirst()

            val entries = try {
                SmbFileClient.listFiles(context, folder)
            } catch (e: Exception) {
                // There is no trash folder as long as nothing was deleted.
                if (folder == trashUrl) continue
                throw e
            }
            searched++
            onProgress(searched)

            for (entry in entries) {
                if (entry.isDirectory) {
                    queue.add(entry.url)
                } else if (entry.isMedia) {
                    validKeys.add(SmbThumbnailCache.cacheKey(entry.url, entry.size, entry.lastModified))
                }
            }
        }
        if (OperationCancel.isRequested()) return null

        val cacheUrl = SmbPath.toUrl(smb.host, smb.share, SmbThumbnailCache.CACHE_DIR)
        val cached = try {
            SmbFileClient.listFiles(context, cacheUrl).filter { !it.isDirectory }
        } catch (e: Exception) {
            // No thumbnail folder yet: nothing to clean.
            emptyList()
        }
        val unusedNames = SmbThumbnailCache.unusedNames(cached.map { it.name }, validKeys).toSet()
        return Scan(root, cached.filter { it.name in unusedNames })
    }

    /** Deletes the files found by [scan]. */
    fun delete(
        context: Context,
        scan: Scan,
        onProgress: (done: Int, total: Int) -> Unit
    ): FileTransfer.Result {
        val smb = SmbPath.parse(scan.shareRoot)
            ?: return FileTransfer.Result(0, scan.unused.size, emptyList(), emptyList())
        var done = 0
        var cancelled = false
        val failures = mutableListOf<FileTransfer.Failure>()

        for ((index, file) in scan.unused.withIndex()) {
            if (OperationCancel.isRequested()) {
                cancelled = true
                break
            }
            val ok = try {
                SmbClient.deleteFile(
                    context, smb.host, smb.share, "${SmbThumbnailCache.CACHE_DIR}/${file.name}"
                )
            } catch (e: Exception) {
                false
            }
            if (ok) done++ else failures.add(FileTransfer.Failure(file.url, FileTransfer.Reason.DELETE_FAILED))
            onProgress(index + 1, scan.unused.size)
        }
        return FileTransfer.Result(done, failures.size, emptyList(), emptyList(), failures, cancelled)
    }

    // ---------------------------------------------------------------- screens

    /** Asks for confirmation, searches the share and offers to delete what was found. */
    fun start(activity: AppCompatActivity, anyUrlOnShare: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.thumb_cleanup_menu)
            .setMessage(R.string.thumb_cleanup_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> search(activity, anyUrlOnShare) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun search(activity: AppCompatActivity, anyUrlOnShare: String) {
        var found: Scan? = null
        var failed = false

        FileOperationDialogs.runWithProgress(
            activity = activity,
            titleRes = R.string.thumb_cleanup_scanning,
            total = 0,
            progressTextRes = R.string.thumb_cleanup_scan_progress,
            reportResult = false,
            work = { onProgress ->
                try {
                    found = scan(activity.applicationContext, anyUrlOnShare) { onProgress(it, it) }
                } catch (e: Exception) {
                    android.util.Log.e("ThumbnailCleanup", "Could not search the share", e)
                    failed = true
                }
                FileTransfer.Result(
                    0, if (failed) 1 else 0, emptyList(), emptyList(),
                    cancelled = OperationCancel.isRequested()
                )
            },
            onDone = { result ->
                val scan = found
                when {
                    result.cancelled -> Unit
                    failed || scan == null ->
                        Toast.makeText(activity, R.string.thumb_cleanup_failed, Toast.LENGTH_LONG).show()
                    scan.unused.isEmpty() ->
                        Toast.makeText(activity, R.string.thumb_cleanup_nothing, Toast.LENGTH_LONG).show()
                    else -> offerDelete(activity, scan)
                }
            }
        )
    }

    private fun offerDelete(activity: AppCompatActivity, scan: Scan) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.thumb_cleanup_menu)
            .setMessage(
                activity.getString(
                    R.string.thumb_cleanup_found,
                    scan.unused.size,
                    Formatter.formatShortFileSize(activity, scan.totalBytes)
                )
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                FileOperationDialogs.runWithProgress(
                    activity = activity,
                    titleRes = R.string.file_op_deleting,
                    total = scan.unused.size,
                    work = { onProgress -> delete(activity.applicationContext, scan, onProgress) },
                    onDone = { }
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
