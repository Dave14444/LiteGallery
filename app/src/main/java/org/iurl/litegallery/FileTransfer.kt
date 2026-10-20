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

    class Result(
        /** Number of files processed successfully. */
        val done: Int,
        val failed: Int,
        /** New files that were created (local paths or remote URLs). */
        val createdPaths: List<String>,
        /** Source files that no longer exist at their old location. */
        val removedPaths: List<String>
    )

    private const val BUFFER_SIZE = 256 * 1024

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
        var done = 0
        var failed = 0

        sourcePaths.forEachIndexed { index, sourcePath ->
            val ok = try {
                transferOne(
                    context, sourcePath, destFolder, destSource, mode,
                    overrideLastModified, created, removed
                )
            } catch (e: Exception) {
                android.util.Log.e("FileTransfer", "Transfer failed: $sourcePath", e)
                false
            }
            if (ok) done++ else failed++
            onProgress(index + 1, sourcePaths.size)
        }

        scanLocalPaths(context, created + removed)
        return Result(done, failed, created, removed)
    }

    private fun transferOne(
        context: Context,
        sourcePath: String,
        destFolder: String,
        destSource: MediaSource?,
        mode: Mode,
        overrideLastModified: Long,
        created: MutableList<String>,
        removed: MutableList<String>
    ): Boolean {
        val sourceSource = MediaSourceRegistry.forPath(sourcePath)
        val name = fileNameOf(sourcePath)

        // Moving a file into the folder it is already in: nothing to do.
        if (mode == Mode.MOVE && parentOf(sourcePath, sourceSource) == destFolder.trimEnd('/')) {
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
        val input: InputStream = if (sourceSource == null) {
            FileInputStream(sourcePath)
        } else {
            sourceSource.openInput(context, sourcePath)
        }
        val newPath = input.use {
            if (destSource == null) {
                writeLocal(File(destFolder), name, it, lastModified)
            } else {
                destSource.writeNew(context, destFolder, name, it, lastModified)
            }
        } ?: return false
        created.add(newPath)

        if (mode == Mode.MOVE) {
            val deleted = if (sourceSource == null) {
                deleteLocalFile(File(sourcePath))
            } else {
                sourceSource.deleteFile(context, sourcePath)
            }
            if (!deleted) return false
            removed.add(sourcePath)
        }
        return true
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
        var done = 0
        var failed = 0

        items.forEachIndexed { index, item ->
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
                failed++
            }
            onProgress(index + 1, items.size)
        }

        scanLocalPaths(context, removed)
        return Result(done, failed, emptyList(), removed)
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
    private fun uniqueFile(dir: File, name: String): File {
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
        MediaScannerConnection.scanFile(context, local.toTypedArray(), null, null)
    }
}
