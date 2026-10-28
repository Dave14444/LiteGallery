package org.iurl.litegallery

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Copy, move and delete for media files, on the phone and on any [MediaSource] that supports
 * file operations (e.g. a network share). All functions block: call them off the main thread.
 *
 * Paths without a registered [MediaSource] are local file paths.
 */
object FileTransfer {

    enum class Mode { COPY, MOVE }

    enum class Reason {
        /** The data could not be copied (no space, connection lost, no permission, ...). */
        COPY_FAILED,

        /** The copy has a different size than the original; the original was kept. */
        SIZE_MISMATCH,

        /** The copy is fine, but the original could not be deleted. */
        DELETE_FAILED,
        OTHER
    }

    /** A file that could not be processed, and why. */
    class Failure(val path: String, val reason: Reason)

    class Result(
        /** Number of files processed successfully. */
        val done: Int,
        val failed: Int,
        /** New files that were created (local paths or remote URLs). */
        val createdPaths: List<String>,
        /** Source files that no longer exist at their old location. */
        val removedPaths: List<String>,
        /** The files counted in [failed], with the reason. */
        val failures: List<Failure> = emptyList(),
        /** True if the user stopped the operation before all files were processed. */
        val cancelled: Boolean = false
    )

    private const val BUFFER_SIZE = 256 * 1024

    /** Tells MediaStore about local files; replaceable so tests need no media scanner. */
    internal var localScanner: (Context, Array<String>) -> Unit = { context, paths ->
        MediaScannerConnection.scanFile(context, paths, null, null)
    }

    // ---------------------------------------------------------------- copy / move

    fun transfer(
        context: Context,
        sourcePaths: List<String>,
        destFolder: String,
        mode: Mode,
        onProgress: (done: Int, total: Int) -> Unit,
        /** If > 0, the new files get this modification time instead of the source file's. */
        overrideLastModified: Long = 0L
    ): Result {
        val destSource = MediaSourceRegistry.forPath(destFolder)
        val created = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val failures = mutableListOf<Failure>()
        var done = 0
        var cancelled = false
        // For the progress in MB: the sizes that are known without asking the network.
        OperationProgress.addTotal(sourcePaths.sumOf { knownSizeOf(it).coerceAtLeast(0L) })

        for ((index, sourcePath) in sourcePaths.withIndex()) {
            if (OperationCancel.isRequested()) {
                cancelled = true
                break
            }
            val ok = try {
                transferOne(
                    context, sourcePath, destFolder, destSource, mode,
                    overrideLastModified, created, removed, failures
                )
            } catch (e: Exception) {
                android.util.Log.e("FileTransfer", "Transfer failed: $sourcePath", e)
                false
            }
            if (ok) {
                done++
            } else if (OperationCancel.isRequested()) {
                // The file was interrupted by the cancel request: not an error.
                failures.removeAll { it.path == sourcePath }
                cancelled = true
                break
            } else if (failures.none { it.path == sourcePath }) {
                failures.add(Failure(sourcePath, Reason.OTHER))
            }
            onProgress(index + 1, sourcePaths.size)
        }

        scanLocalPaths(context, created + removed)
        return Result(done, failures.size, created, removed, failures.toList(), cancelled)
    }

    private fun transferOne(
        context: Context,
        sourcePath: String,
        destFolder: String,
        destSource: MediaSource?,
        mode: Mode,
        overrideLastModified: Long,
        created: MutableList<String>,
        removed: MutableList<String>,
        failures: MutableList<Failure>
    ): Boolean {
        val sourceSource = MediaSourceRegistry.forPath(sourcePath)
        val name = fileNameOf(sourcePath)
        // Known before anything happens: after a move the old path is gone.
        val knownSize = knownSizeOf(sourcePath)

        // Moving a file into the folder it is already in: nothing to do.
        if (mode == Mode.MOVE && parentOf(sourcePath, sourceSource) == destFolder.trimEnd('/')) {
            OperationProgress.addDone(knownSize)
            return true
        }

        // Moves that need no data transfer: rename on the phone / on the same share.
        if (mode == Mode.MOVE) {
            val moved = if (sourceSource == null && destSource == null) {
                moveLocal(sourcePath, destFolder)
            } else if (sourceSource != null && sourceSource === destSource) {
                sourceSource.moveWithin(context, sourcePath, destFolder, name)
            } else {
                null
            }
            if (moved != null) {
                // Nothing to copy: the whole file counts as done at once.
                OperationProgress.addDone(knownSize)
                created.add(moved)
                removed.add(sourcePath)
                return true
            }
        }

        // Copy the data, keeping the original modification time.
        val lastModified = when {
            overrideLastModified > 0L -> overrideLastModified
            sourceSource == null -> File(sourcePath).lastModified()
            else -> sourceSource.lastModified(context, sourcePath)
        }
        // What the copy has to look like when it is finished (-1: the source cannot tell).
        val expectedSize = sizeOf(context, sourcePath, sourceSource)

        val input: InputStream = CancellableInputStream(
            if (sourceSource == null) {
                FileInputStream(sourcePath)
            } else {
                sourceSource.openInput(context, sourcePath)
            }
        ) { bytes -> OperationProgress.addDone(bytes.toLong()) }
        val newPath = input.use {
            if (destSource == null) {
                writeLocal(File(destFolder), name, it, lastModified)
            } else {
                destSource.writeNew(context, destFolder, name, it, lastModified)
            }
        }
        if (newPath == null) {
            failures.add(Failure(sourcePath, Reason.COPY_FAILED))
            return false
        }

        // Check the copy before anything is deleted: ask the destination how big the new file is.
        if (!sizeMatches(expectedSize, sizeOf(context, newPath, destSource), mode)) {
            android.util.Log.e("FileTransfer", "Size mismatch for $sourcePath -> $newPath")
            deleteQuietly(context, newPath, destSource)
            failures.add(Failure(sourcePath, Reason.SIZE_MISMATCH))
            return false
        }
        created.add(newPath)

        if (mode == Mode.MOVE) {
            val deleted = if (sourceSource == null) {
                deleteLocalFile(File(sourcePath))
            } else {
                sourceSource.deleteFile(context, sourcePath)
            }
            if (!deleted) {
                failures.add(Failure(sourcePath, Reason.DELETE_FAILED))
                return false
            }
            removed.add(sourcePath)
        }
        return true
    }

    /**
     * Whether a copy of [actual] bytes is acceptable for an original of [expected] bytes.
     * If one of the sizes is unknown (-1) a copy is accepted, but never when moving: then the
     * original would be deleted without having been checked.
     */
    internal fun sizeMatches(expected: Long, actual: Long, mode: Mode): Boolean {
        if (expected < 0L || actual < 0L) return mode == Mode.COPY
        return expected == actual
    }

    /** Size of [path] if it is known without network access (files on the phone, listed files). */
    private fun knownSizeOf(path: String): Long {
        val source = MediaSourceRegistry.forPath(path)
        if (source != null) return source.cachedFileSize(path)
        val file = File(path)
        return if (file.isFile) file.length() else -1L
    }

    private fun sizeOf(context: Context, path: String, source: MediaSource?): Long =
        try {
            if (source == null) {
                val file = File(path)
                if (file.isFile) file.length() else -1L
            } else {
                source.fileSize(context, path)
            }
        } catch (e: Exception) {
            -1L
        }

    private fun deleteQuietly(context: Context, path: String, source: MediaSource?) {
        try {
            if (source == null) File(path).delete() else source.deleteFile(context, path)
        } catch (e: Exception) {
            android.util.Log.e("FileTransfer", "Could not remove $path", e)
        }
    }

    // ---------------------------------------------------------------- delete

    /**
     * Deletes [items]. Local files go to the trash when [moveToTrash] is set (like in the
     * viewer); files on a [MediaSource] are always deleted for good.
     */
    fun delete(
        context: Context,
        items: List<MediaItemSkeleton>,
        moveToTrash: Boolean,
        onProgress: (done: Int, total: Int) -> Unit
    ): Result {
        val removed = mutableListOf<String>()
        val failures = mutableListOf<Failure>()
        var done = 0
        var cancelled = false

        for ((index, item) in items.withIndex()) {
            if (OperationCancel.isRequested()) {
                cancelled = true
                break
            }
            val ok = try {
                deleteOne(context, item, moveToTrash)
            } catch (e: Exception) {
                android.util.Log.e("FileTransfer", "Delete failed: ${item.path}", e)
                false
            }
            if (ok) {
                done++
                removed.add(item.path)
            } else {
                failures.add(Failure(item.path, Reason.DELETE_FAILED))
            }
            onProgress(index + 1, items.size)
        }

        scanLocalPaths(context, removed)
        return Result(done, failures.size, emptyList(), removed, failures.toList(), cancelled)
    }

    private fun deleteOne(context: Context, item: MediaItemSkeleton, moveToTrash: Boolean): Boolean {
        val source = MediaSourceRegistry.forPath(item.path)
        if (source != null) {
            return if (moveToTrash && source.supportsTrash(item.path)) {
                source.moveToTrash(context, item.path)
            } else {
                source.deleteFile(context, item.path)
            }
        }

        val file = File(item.path)
        if (!file.exists()) return true
        if (moveToTrash) return trashLocal(context, item, file)

        if (file.delete()) return true
        // Fall back to MediaStore (e.g. files owned by another app).
        val uri = item.thumbnailModel() as? Uri ?: return false
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun trashLocal(context: Context, item: MediaItemSkeleton, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uri = item.thumbnailModel() as? Uri
            if (uri != null) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_TRASHED, 1)
                    }
                    if (context.contentResolver.update(uri, values, null, null) > 0) return true
                } catch (_: Exception) {
                    // Fall through to the app trash.
                }
            }
        }
        return moveToAppTrash(context, file)
    }

    private fun moveToAppTrash(context: Context, file: File): Boolean {
        val parent = file.parentFile ?: return false
        val originalName = file.name

        for (index in 0..9999) {
            val candidateName = if (index == 0) {
                "${TrashBinStore.TRASH_FILE_PREFIX}$originalName"
            } else {
                "${TrashBinStore.TRASH_FILE_PREFIX}$index-$originalName"
            }
            val candidate = File(parent, candidateName)
            if (candidate.exists()) continue
            if (!file.renameTo(candidate)) return false
            TrashBinStore.rememberTrashedFile(
                context = context,
                trashedUri = Uri.fromFile(candidate),
                originalUri = Uri.fromFile(file),
                originalName = originalName,
                originalPathHint = file.absolutePath
            )
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- local helpers

    fun isLocalPath(path: String): Boolean = MediaSourceRegistry.forPath(path) == null

    private fun fileNameOf(path: String): String =
        path.trimEnd('/').substringAfterLast('/')

    private fun parentOf(path: String, source: MediaSource?): String? =
        if (source == null) File(path).parent else source.parentFolder(path)?.trimEnd('/')

    private fun moveLocal(sourcePath: String, destFolder: String): String? {
        val source = File(sourcePath)
        val dir = File(destFolder)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = uniqueFile(dir, source.name)
        // renameTo fails across storage volumes; the caller then copies instead.
        return if (source.renameTo(target)) target.absolutePath else null
    }

    /** Writes [input] into [folder] on the phone under a unique name; returns the new path. */
    fun writeLocalFile(folder: File, desiredName: String, input: InputStream, lastModified: Long): String? =
        writeLocal(folder, desiredName, input, lastModified)

    private fun writeLocal(dir: File, name: String, input: InputStream, lastModified: Long): String? {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = uniqueFile(dir, name)
        return try {
            target.outputStream().use { output ->
                input.copyTo(output, BUFFER_SIZE)
            }
            if (lastModified > 0L) target.setLastModified(lastModified)
            target.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("FileTransfer", "Could not write ${target.path}", e)
            target.delete()
            null
        }
    }

    /** [name] in [dir], or "name (1).ext", "name (2).ext", ... if it already exists. */
    internal fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate

        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        for (i in 1..9999) {
            val candidateName = if (extension.isEmpty()) "$base ($i)" else "$base ($i).$extension"
            candidate = File(dir, candidateName)
            if (!candidate.exists()) return candidate
        }
        return candidate
    }

    private fun deleteLocalFile(file: File): Boolean = file.delete() || !file.exists()

    /** Tells MediaStore about new / removed local files. */
    private fun scanLocalPaths(context: Context, paths: List<String>) {
        val local = paths.filter { isLocalPath(it) }.distinct()
        if (local.isEmpty()) return
        localScanner(context, local.toTypedArray())
    }
}
