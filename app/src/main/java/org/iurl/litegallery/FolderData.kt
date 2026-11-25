package org.iurl.litegallery

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Small data the app keeps about a folder (which pictures have a star, their photo data).
 *
 * For a folder on a network share it is stored on the share (see MediaSource.readFolderData), so
 * the phone and the tablet see the same. For a folder on the device it is kept in the app's own
 * storage.
 */
object FolderData {

    private const val LOCAL_DIRECTORY = "folder_data"

    /** What is stored for [folderPath] under [key], or null if nothing yet. Blocking; throws if unreadable. */
    fun read(context: Context, folderPath: String, key: String): String? {
        val folder = normalize(folderPath)
        val source = MediaSourceRegistry.forPath(folder)
        if (source != null) return source.readFolderData(context, folder, key)
        val file = localFile(context, folder, key)
        return if (file.isFile) file.readText() else null
    }

    /** Stores [content] for [folderPath] under [key]. Blocking; throws on failure. */
    fun write(context: Context, folderPath: String, key: String, content: String) {
        val folder = normalize(folderPath)
        val source = MediaSourceRegistry.forPath(folder)
        if (source != null) {
            source.writeFolderData(context, folder, key, content)
            return
        }
        val file = localFile(context, folder, key)
        file.parentFile?.mkdirs()
        // Written to a new file first: a reader never sees half of it.
        val temp = File(file.parentFile, file.name + ".new")
        temp.writeText(content)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw java.io.IOException("Could not store $file")
        }
    }

    fun normalize(folderPath: String): String = folderPath.trimEnd('/').ifEmpty { "/" }

    /** The name of the stored data: a hash of the folder (folder names may hold any character). */
    fun fileName(folderPath: String, key: String): String = "${hash(normalize(folderPath))}-$key.json"

    internal fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun localFile(context: Context, folderPath: String, key: String): File =
        File(File(context.filesDir, LOCAL_DIRECTORY), fileName(folderPath, key))
}
