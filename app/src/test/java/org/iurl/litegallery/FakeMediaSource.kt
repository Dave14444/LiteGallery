package org.iurl.litegallery

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * A [MediaSource] that keeps its files in memory (paths start with "fake://"), for testing
 * [FileTransfer]. The switches let a test simulate a broken connection or share.
 */
class FakeMediaSource : MediaSource {

    val files = LinkedHashMap<String, ByteArray>()

    /** Files are stored with only half of their data, like an upload that was cut off. */
    var truncateWrites = false

    /** [fileSize] cannot tell the size (like a share that did not answer). */
    var sizeUnknown = false

    /** [deleteFile] fails and keeps the file. */
    var failDelete = false

    /** Called when [writeNew] starts, before any data is read. */
    var beforeWrite: (() -> Unit)? = null

    override fun handles(path: String): Boolean = path.startsWith("fake://")

    override fun homeEntry(context: Context): MediaFolder? = null

    override fun browseIntent(context: Context): Intent = Intent()

    override val folderIconRes: Int = 0

    override fun canLoadThumbnail(path: String): Boolean = false

    override suspend fun scanFolder(context: Context, folderPath: String): List<MediaItem> = emptyList()

    override fun scanFolderStreamed(context: Context, folderPath: String): Flow<LoadEvent> = emptyFlow()

    override fun createExoMediaSource(
        context: Context,
        path: String
    ): androidx.media3.exoplayer.source.MediaSource = throw UnsupportedOperationException()

    override suspend fun rename(
        context: Context,
        path: String,
        newBaseName: String,
        extension: String
    ): RenameOutcome = RenameOutcome.Failed

    override fun supportsFileOperations(path: String): Boolean = handles(path)

    override fun parentFolder(path: String): String? =
        path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { null }

    override fun openInput(context: Context, path: String): InputStream =
        ByteArrayInputStream(files[path] ?: throw java.io.FileNotFoundException(path))

    override fun fileSize(context: Context, path: String): Long {
        if (sizeUnknown) return -1L
        return files[path]?.size?.toLong() ?: -1L
    }

    override fun writeNew(
        context: Context,
        folderPath: String,
        desiredName: String,
        input: InputStream,
        lastModified: Long
    ): String? {
        beforeWrite?.invoke()
        val data = try {
            input.readBytes()
        } catch (e: java.io.IOException) {
            return null
        }
        val path = folderPath.trimEnd('/') + "/" + desiredName
        files[path] = if (truncateWrites) data.copyOf(data.size / 2) else data
        return path
    }

    override fun deleteFile(context: Context, path: String): Boolean {
        if (failDelete) return false
        files.remove(path)
        return true
    }

    // ---- Whole folders: a folder exists if it was created or holds a file ----

    /** Folders that were created (folders holding a file exist anyway). */
    val folders = LinkedHashSet<String>()

    /** [moveFolderWithin] moves a folder in one step (like a rename on a share). */
    var movesFoldersInOneStep = true

    // "fake://share" is the share itself: no folder operations on it.
    override fun supportsFolderOperations(path: String): Boolean =
        handles(path) && path.removePrefix("fake://").trimEnd('/').contains('/')

    override fun createFolder(context: Context, parentPath: String, name: String): String? {
        val path = parentPath.trimEnd('/') + "/" + name
        folders.add(path)
        return path
    }

    private fun allFolders(): Set<String> {
        val result = LinkedHashSet(folders)
        for (file in files.keys) {
            var parent = file.substringBeforeLast('/')
            while (parent.removePrefix("fake://").contains('/')) {
                result.add(parent)
                parent = parent.substringBeforeLast('/')
            }
        }
        return result
    }

    override fun listFolder(context: Context, folderPath: String): List<FolderChild> {
        val prefix = folderPath.trimEnd('/') + "/"
        fun isChild(path: String) = path.startsWith(prefix) && !path.removePrefix(prefix).contains('/')
        val childFolders = allFolders().filter { isChild(it) }
            .map { FolderChild(it.substringAfterLast('/'), it, true, 0L) }
        val childFiles = files.filterKeys { isChild(it) }
            .map { (path, data) -> FolderChild(path.substringAfterLast('/'), path, false, data.size.toLong()) }
        return childFolders + childFiles
    }

    override fun moveFolderWithin(
        context: Context,
        path: String,
        destFolderPath: String,
        name: String
    ): String? {
        if (!movesFoldersInOneStep || !handles(destFolderPath)) return null
        val oldRoot = path.trimEnd('/')
        val newRoot = destFolderPath.trimEnd('/') + "/" + name
        fun moved(p: String) = if (p == oldRoot || p.startsWith("$oldRoot/")) newRoot + p.removePrefix(oldRoot) else p
        val renamed = files.entries.map { moved(it.key) to it.value }
        files.clear()
        renamed.forEach { (p, data) -> files[p] = data }
        val renamedFolders = folders.map { moved(it) }
        folders.clear()
        folders.addAll(renamedFolders)
        folders.add(newRoot)
        return newRoot
    }

    override fun removeEmptiedFolder(context: Context, path: String): Boolean {
        val root = path.trimEnd('/')
        if (files.keys.any { it.startsWith("$root/") }) return false
        folders.removeAll { it == root || it.startsWith("$root/") }
        return true
    }

    /** What [exportSettings] returns, and what [importSettings] received last. */
    var exportedSettings: org.json.JSONObject? = null
    var importedSettings: org.json.JSONObject? = null

    override val settingsExportKey: String = "fake"

    override fun exportSettings(context: Context): org.json.JSONObject? = exportedSettings

    override fun importSettings(context: Context, data: org.json.JSONObject): Int {
        importedSettings = data
        return 1
    }

    override fun shutdown() {}
}
