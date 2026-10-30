package org.iurl.litegallery

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Copy, move and delete for whole folders, on the phone and on sources with file operations
 * (network shares). The files themselves go through [FileTransfer], so the same checks apply
 * (size of each copy, originals deleted only after a good copy). All functions block: call
 * them off the main thread.
 */
object FolderTransfer {

    /** What a folder holds, read before an operation starts. */
    class Tree(
        /** The folder itself. */
        val root: String,
        /** Its sub folders relative to [root] ("a", "a/b"); a folder comes before its own sub folders. */
        val subFolders: List<String>,
        /** The files by folder relative to [root] ("" is [root] itself). */
        val files: Map<String, List<String>>,
        /** On the phone: files in the trash (or still being written) were left out. */
        val hasLeftOutFiles: Boolean = false
    ) {
        val fileCount: Int get() = files.values.sumOf { it.size }
        val allFiles: List<String> get() = files.values.flatten()
    }

    private const val PENDING_PREFIX = ".pending-"

    // A storage volume itself or anything above it: "/storage", "/storage/emulated",
    // "/storage/emulated/0", "/storage/1234-ABCD" (an SD card).
    private val VOLUME_OR_ABOVE = Regex("^/storage(/[^/]+)?$|^/storage/emulated(/[^/]+)?$")

    // ---------------------------------------------------------------- reading

    /** Whether the folder [path] may be copied, moved and deleted as a whole. */
    fun canHandle(path: String): Boolean {
        val source = MediaSourceRegistry.forPath(path)
        if (source != null) return source.supportsFolderOperations(path)
        return isMovableLocalFolder(path, Environment.getExternalStorageDirectory().absolutePath)
    }

    /** A folder on the phone, but never a storage volume itself (the phone's storage, an SD card). */
    internal fun isMovableLocalFolder(path: String, phoneRoot: String): Boolean {
        val folder = path.trimEnd('/')
        val root = phoneRoot.trimEnd('/')
        if (folder.isEmpty() || folder == root || VOLUME_OR_ABOVE.matches(folder)) return false
        return folder.startsWith("$root/") || folder.startsWith("/storage/")
    }

    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** The folder that contains [path], or null. */
    fun parentOf(path: String): String? {
        val source = MediaSourceRegistry.forPath(path)
        return if (source != null) source.parentFolder(path)?.trimEnd('/') else File(path).parent
    }

    /**
     * Reads everything in [root] that an operation takes along. Throws if a folder cannot be
     * read, and a [CancellationException] once [isCancelled] says so.
     */
    fun scan(context: Context, root: String, isCancelled: () -> Boolean = { false }): Tree {
        val source = MediaSourceRegistry.forPath(root)
        val top = if (source == null) File(root).absolutePath else root.trimEnd('/')
        val subFolders = mutableListOf<String>()
        val files = LinkedHashMap<String, List<String>>()
        var leftOut = false

        fun walk(folder: String, relative: String) {
            if (isCancelled()) throw CancellationException()
            val children = if (source == null) {
                val entries = File(folder).listFiles() ?: throw IOException("Cannot read $folder")
                if (entries.any { !it.isDirectory && !isPartOfLocalFolder(it.name, false) }) leftOut = true
                entries
                    .filter { isPartOfLocalFolder(it.name, it.isDirectory) }
                    .map { FolderChild(it.name, it.absolutePath, it.isDirectory, if (it.isDirectory) 0L else it.length()) }
            } else {
                source.listFolder(context, folder)
            }
            files[relative] = children.filter { !it.isDirectory }.map { it.path }
            for (child in children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }) {
                val childRelative = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                subFolders.add(childRelative)
                walk(child.path, childRelative)
            }
        }

        walk(top, "")
        return Tree(top, subFolders, files, leftOut)
    }

    /**
     * What an operation on a folder on the phone takes along: everything except hidden folders
     * (caches such as .thumbnails) and files that are in the trash or still being written.
     */
    internal fun isPartOfLocalFolder(name: String, isDirectory: Boolean): Boolean =
        if (isDirectory) {
            !name.startsWith(".")
        } else {
            !name.startsWith(TrashBinStore.TRASH_FILE_PREFIX) && !name.startsWith(PENDING_PREFIX)
        }

    /** Names of everything in [folder], in lower case (to find a free name). */
    fun namesIn(context: Context, folder: String): Set<String> {
        val source = MediaSourceRegistry.forPath(folder)
        val names = if (source == null) {
            File(folder).list()?.toList() ?: throw IOException("Cannot read $folder")
        } else {
            source.listFolder(context, folder).map { it.name }
        }
        return names.map { it.lowercase() }.toSet()
    }

    /**
     * Whether [name] can be a folder's new name: not empty, none of the characters Windows and
     * NAS systems refuse, no dot at the start (the folder would be hidden) or at the end.
     */
    internal fun isValidFolderName(name: String): Boolean =
        name.isNotBlank() && name == name.trim() &&
            !name.startsWith(".") && !name.endsWith(".") &&
            name.none { it in "/\\:*?\"<>|" || it < ' ' }

    /** [name], or "name (1)", "name (2)", ... whichever is not in [taken] (lower case). */
    internal fun freeName(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        for (i in 1..9999) {
            val candidate = "$name ($i)"
            if (candidate.lowercase() !in taken) return candidate
        }
        return "$name (${System.currentTimeMillis()})"
    }

    // ---------------------------------------------------------------- copy / move

    /**
     * Copies or moves the folder of [tree] into [destParent] as [targetName]. With [merge] the
     * files go into the existing folder of that name (names that are taken get " (1)");
     * otherwise the folder is new. After a move the old folder is removed once it is empty.
     */
    fun transfer(
        context: Context,
        tree: Tree,
        destParent: String,
        targetName: String,
        merge: Boolean,
        mode: FileTransfer.Mode,
        onProgress: (done: Int, total: Int) -> Unit
    ): FileTransfer.Result {
        val total = tree.fileCount

        // On the phone or on the same share a folder moves in one step, however big it is.
        if (mode == FileTransfer.Mode.MOVE && !merge) {
            val moved = try {
                moveWhole(context, tree, destParent, targetName)
            } catch (e: Exception) {
                android.util.Log.w("FolderTransfer", "Could not move ${tree.root} in one step", e)
                null
            }
            if (moved != null) {
                onProgress(total, total)
                return moved
            }
        }

        val destSource = MediaSourceRegistry.forPath(destParent)
        val created = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val failures = mutableListOf<FileTransfer.Failure>()
        var done = 0
        var cancelled = false

        val targetRoot = createFolder(context, destParent, targetName, destSource)
            ?: return FileTransfer.Result(
                0, 1, emptyList(), emptyList(),
                listOf(FileTransfer.Failure(tree.root, FileTransfer.Reason.COPY_FAILED, destSource?.lastError()))
            )
        val targets = mutableMapOf("" to targetRoot)
        for (relative in tree.subFolders) {
            if (OperationCancel.isRequested()) break
            // Without its parent a folder cannot be made; its files are reported below.
            val parent = targets[relative.substringBeforeLast('/', "")] ?: continue
            createFolder(context, parent, relative.substringAfterLast('/'), destSource)
                ?.let { targets[relative] = it }
        }

        // The progress in MB for the whole folder at once (the sizes are known from reading it).
        OperationProgress.addTotal(tree.allFiles.sumOf { FileTransfer.knownSizeOf(it).coerceAtLeast(0L) })

        var finished = 0
        for ((relative, files) in tree.files) {
            if (files.isEmpty()) continue
            if (OperationCancel.isRequested()) {
                cancelled = true
                break
            }
            val target = targets[relative]
            if (target == null) {
                files.forEach { failures.add(FileTransfer.Failure(it, FileTransfer.Reason.COPY_FAILED)) }
                finished += files.size
                onProgress(finished, total)
                continue
            }
            val base = finished
            val result = FileTransfer.transfer(
                context, files, target, mode,
                onProgress = { filesDone, _ -> onProgress(base + filesDone, total) },
                countBytes = false
            )
            done += result.done
            created.addAll(result.createdPaths)
            removed.addAll(result.removedPaths)
            failures.addAll(result.failures)
            finished += files.size
            if (result.cancelled) {
                cancelled = true
                break
            }
        }

        if (mode == FileTransfer.Mode.MOVE && !cancelled && failures.isEmpty() &&
            !removeEmptied(context, tree.root)
        ) {
            failures.add(FileTransfer.Failure(tree.root, FileTransfer.Reason.FOLDER_NOT_REMOVED))
        }
        return FileTransfer.Result(done, failures.size, created, removed, failures, cancelled)
    }

    /** A rename of the whole folder, or null if that is not possible here. */
    private fun moveWhole(
        context: Context,
        tree: Tree,
        destParent: String,
        targetName: String
    ): FileTransfer.Result? {
        val source = MediaSourceRegistry.forPath(tree.root)
        val destSource = MediaSourceRegistry.forPath(destParent)
        val newRoot = when {
            source == null && destSource == null -> {
                // Files in the phone's trash must stay where the trash expects them.
                if (tree.hasLeftOutFiles) return null
                val target = File(destParent, targetName)
                // renameTo fails across storage volumes: then the files are copied.
                if (target.exists() || !File(tree.root).renameTo(target)) return null
                target.absolutePath
            }
            source != null && source === destSource ->
                source.moveFolderWithin(context, tree.root, destParent, targetName) ?: return null
            else -> return null
        }

        val oldFiles = tree.allFiles
        val newFiles = oldFiles.map { newRoot + it.removePrefix(tree.root) }
        if (source == null) FileTransfer.localScanner(context, (oldFiles + newFiles).toTypedArray())
        return FileTransfer.Result(oldFiles.size, 0, newFiles, oldFiles)
    }

    /** Creates (or finds) folder [name] in [parent]; returns its path, or null. */
    private fun createFolder(context: Context, parent: String, name: String, source: MediaSource?): String? {
        if (source != null) return source.createFolder(context, parent, name)
        val folder = File(parent, name)
        return if (folder.isDirectory || folder.mkdirs()) folder.absolutePath else null
    }

    // ---------------------------------------------------------------- delete

    /**
     * Deletes the folder of [tree]. With [moveToTrash] the files go to the trash: on a share
     * all of them, on the phone the pictures and videos (other files stay, and so does the
     * folder then). Without, everything is deleted for good.
     */
    fun delete(
        context: Context,
        tree: Tree,
        moveToTrash: Boolean,
        onProgress: (done: Int, total: Int) -> Unit
    ): FileTransfer.Result {
        val source = MediaSourceRegistry.forPath(tree.root)
        val useTrash = moveToTrash && (source?.supportsTrash(tree.root) ?: true)
        val items = if (source == null) {
            localItems(context, tree, onlyMedia = useTrash)
        } else {
            tree.allFiles.map { skeletonOf(it, MediaItem.NO_MEDIASTORE_ID, isVideoName(it)) }
        }

        val result = FileTransfer.delete(context, items, useTrash, onProgress)
        if (result.cancelled || result.failed > 0) return result

        if (source == null && useTrash) {
            // Pictures in the trash stay in their folder until the trash is emptied.
            removeEmptiedLocal(File(tree.root))
            return result
        }
        if (removeEmptied(context, tree.root)) return result
        return FileTransfer.Result(
            result.done, result.failed + 1, result.createdPaths, result.removedPaths,
            result.failures + FileTransfer.Failure(tree.root, FileTransfer.Reason.FOLDER_NOT_REMOVED),
            false
        )
    }

    /** The files of a folder on the phone for [FileTransfer.delete], with their MediaStore ids. */
    private fun localItems(context: Context, tree: Tree, onlyMedia: Boolean): List<MediaItemSkeleton> {
        val known = localMediaIds(context, tree.root)
        return tree.allFiles.mapNotNull { path ->
            val entry = known[path]
            if (onlyMedia && entry == null && !QuickBackup.isMedia(File(path))) return@mapNotNull null
            skeletonOf(path, entry?.first ?: MediaItem.NO_MEDIASTORE_ID, entry?.second ?: isVideoName(path))
        }
    }

    /**
     * MediaStore ids (and whether it is a video) of the pictures and videos below [root]: with
     * the id a picture goes into the system's trash, like when it is deleted in the viewer.
     */
    @Suppress("DEPRECATION") // DATA is the only way to find files by their folder.
    private fun localMediaIds(context: Context, root: String): Map<String, Pair<Long, Boolean>> {
        val found = HashMap<String, Pair<Long, Boolean>>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )
        // Every path that starts with "root/" ('0' is the character after '/').
        val selection = "${MediaStore.Files.FileColumns.DATA} >= ? AND " +
            "${MediaStore.Files.FileColumns.DATA} < ? AND " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val folder = root.trimEnd('/')
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                arrayOf("$folder/", "${folder}0"),
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathColumn) ?: continue
                    val isVideo = cursor.getInt(typeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    found[path] = cursor.getLong(idColumn) to isVideo
                }
            }
        } catch (e: Exception) {
            // Without ids the pictures go to the app's own trash instead.
            android.util.Log.w("FolderTransfer", "Could not look up the pictures in $root", e)
        }
        return found
    }

    private fun skeletonOf(path: String, id: Long, isVideo: Boolean) = MediaItemSkeleton(
        id = id,
        path = path,
        name = nameOf(path),
        dateModified = 0L,
        size = 0L,
        isVideo = isVideo
    )

    private fun isVideoName(path: String): Boolean = MediaMimeTypes.fromPath(path).startsWith("video/")

    // ---------------------------------------------------------------- removing the old folder

    private fun removeEmptied(context: Context, root: String): Boolean {
        val source = MediaSourceRegistry.forPath(root)
        return if (source != null) source.removeEmptiedFolder(context, root) else removeEmptiedLocal(File(root))
    }

    /**
     * Deletes [dir] and its sub folders as far as they are empty. What operations leave alone
     * (files in the trash, hidden folders) keeps its folder. Returns false if a file that should
     * have gone is still there.
     */
    internal fun removeEmptiedLocal(dir: File): Boolean {
        if (!dir.exists()) return true
        val entries = dir.listFiles() ?: return false
        var clean = true
        for (entry in entries) {
            if (entry.isDirectory) {
                if (isPartOfLocalFolder(entry.name, true) && !removeEmptiedLocal(entry)) clean = false
            } else if (isPartOfLocalFolder(entry.name, false)) {
                clean = false
            }
        }
        // Only succeeds if nothing is left in it.
        dir.delete()
        return clean
    }
}
