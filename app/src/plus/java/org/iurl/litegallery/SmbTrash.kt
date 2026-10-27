package org.iurl.litegallery

import android.content.Context

/**
 * Trash bin on a network share: "deleted" files are moved into a hidden folder at the root of
 * the share instead of being erased, together with a small note about where they came from.
 * The trash lives on the share, so it survives reinstalling the app and is the same on every
 * device that uses the share. Moving a file within a share is a rename, so nothing is copied.
 */
object SmbTrash {

    const val TRASH_DIR = ".LiteGalleryTrash"
    private const val META_SUFFIX = ".meta"
    private const val META_VERSION = "v1"

    /** "<millis>-<4 digits>__<original name>" */
    private val TRASHED_NAME = Regex("^(\\d{10,})-(\\d{4})__(.*)$")

    data class Item(
        val trashedUrl: String,
        /** Where the file was before it was deleted (a best guess if the note is missing). */
        val originalUrl: String,
        val deletedAt: Long,
        val size: Long,
        val isVideo: Boolean
    ) {
        val originalName: String get() = originalUrl.trimEnd('/').substringAfterLast('/')
        val originalFolderName: String
            get() = originalUrl.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
    }

    /** URL of the share root that contains [anyUrl] (the trash lives there). */
    fun shareRootOf(anyUrl: String): String? {
        val smb = SmbPath.parse(anyUrl) ?: return null
        return SmbPath.toUrl(smb.host, smb.share, "")
    }

    /** Moves [url] into the trash of its share. The file keeps its modification time. */
    fun moveToTrash(context: Context, url: String): Boolean {
        val smb = SmbPath.parse(url) ?: return false
        val name = smb.path.substringAfterLast('/')
        val id = "${System.currentTimeMillis()}-${(1000..9999).random()}"
        val trashedPath = "$TRASH_DIR/${id}__$name"
        val note = "$META_VERSION\n$url\n"

        return try {
            // The note first: without it the file could not be put back where it belongs.
            SmbClient.writeBytes(
                context, smb.host, smb.share, trashedPath + META_SUFFIX,
                note.toByteArray(Charsets.UTF_8)
            )
            if (SmbClient.rename(context, smb.host, smb.share, smb.path, trashedPath)) {
                SmbFileMetaCache.remove(url)
                true
            } else {
                SmbClient.deleteFile(context, smb.host, smb.share, trashedPath + META_SUFFIX)
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbTrash", "Could not move to trash: $url", e)
            false
        }
    }

    /**
     * Splits a name in the trash ("<millis>-<4 digits>__<original name>") into the time of
     * deletion and the original name; null if it does not follow that scheme.
     */
    internal fun parseTrashedName(name: String): Pair<Long, String>? {
        val match = TRASHED_NAME.matchEntire(name) ?: return null
        val millis = match.groupValues[1].toLongOrNull() ?: return null
        return millis to match.groupValues[3]
    }

    /** Everything in the trash of the share at [shareRootUrl], newest first. */
    fun list(context: Context, shareRootUrl: String): List<Item> {
        val root = SmbPath.parse(shareRootUrl) ?: return emptyList()
        val trashUrl = SmbPath.toUrl(root.host, root.share, TRASH_DIR)

        val entries = try {
            SmbFileClient.listFiles(context, trashUrl)
        } catch (e: Exception) {
            // No trash folder yet.
            return emptyList()
        }
        val names = entries.filter { !it.isDirectory }.map { it.name }.toSet()

        return entries
            .filter { !it.isDirectory && !it.name.endsWith(META_SUFFIX) }
            .map { entry ->
                val parsed = parseTrashedName(entry.name)
                val deletedAt = parsed?.first ?: entry.lastModified
                val fallbackName = parsed?.second ?: entry.name
                val original = if (names.contains(entry.name + META_SUFFIX)) {
                    readOriginalUrl(context, root, entry.name)
                } else {
                    null
                }
                Item(
                    trashedUrl = entry.url,
                    originalUrl = original ?: SmbPath.toUrl(root.host, root.share, fallbackName),
                    deletedAt = deletedAt,
                    size = entry.size,
                    isVideo = entry.isVideo
                )
            }
            .sortedByDescending { it.deletedAt }
    }

    private fun readOriginalUrl(context: Context, root: SmbPath, trashedName: String): String? {
        return try {
            SmbClient.openInputStream(
                context, root.host, root.share, "$TRASH_DIR/$trashedName$META_SUFFIX"
            ).use { stream ->
                val lines = stream.readBytes().toString(Charsets.UTF_8).lines()
                if (lines.firstOrNull() == META_VERSION) lines.getOrNull(1)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Puts [item] back: into its old folder (created again if it is gone), under a new name if
     * the old name is taken now. Returns true on success.
     */
    fun restore(context: Context, item: Item): Boolean {
        val trashed = SmbPath.parse(item.trashedUrl) ?: return false
        val original = SmbPath.parse(item.originalUrl) ?: return false
        if (!trashed.host.equals(original.host, ignoreCase = true) ||
            !trashed.share.equals(original.share, ignoreCase = true)
        ) {
            return false
        }

        return try {
            val folder = original.path.substringBeforeLast('/', "")
            if (!ensureFolders(context, original, folder)) return false

            val target = uniquePath(context, original) ?: return false
            if (!SmbClient.rename(context, trashed.host, trashed.share, trashed.path, target)) {
                return false
            }
            SmbClient.deleteFile(context, trashed.host, trashed.share, trashed.path + META_SUFFIX)
            SmbFileMetaCache.remove(item.trashedUrl)
            true
        } catch (e: Exception) {
            android.util.Log.e("SmbTrash", "Could not restore ${item.trashedUrl}", e)
            false
        }
    }

    /** Erases [item] for good. */
    fun deletePermanently(context: Context, item: Item): Boolean {
        val trashed = SmbPath.parse(item.trashedUrl) ?: return false
        val deleted = SmbClient.deleteFile(context, trashed.host, trashed.share, trashed.path)
        if (deleted) {
            SmbClient.deleteFile(context, trashed.host, trashed.share, trashed.path + META_SUFFIX)
            SmbFileMetaCache.remove(item.trashedUrl)
        }
        return deleted
    }

    /** Erases everything older than [retentionDays]. Returns how many files were removed. */
    fun purgeExpired(context: Context, shareRootUrl: String, retentionDays: Int): Int {
        if (retentionDays <= 0) return 0
        val limit = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
        return list(context, shareRootUrl)
            .filter { it.deletedAt in 1 until limit }
            .count { deletePermanently(context, it) }
    }

    /** Creates every folder of [relativeFolder] that does not exist yet. */
    private fun ensureFolders(context: Context, share: SmbPath, relativeFolder: String): Boolean {
        if (relativeFolder.isEmpty()) return true
        var current = ""
        for (segment in relativeFolder.split('/').filter { it.isNotEmpty() }) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (!SmbClient.createDirectory(context, share.host, share.share, current)) return false
        }
        return true
    }

    private fun uniquePath(context: Context, original: SmbPath): String? {
        if (!SmbClient.fileExists(context, original.host, original.share, original.path)) {
            return original.path
        }
        val folder = original.path.substringBeforeLast('/', "")
        val fileName = original.path.substringAfterLast('/')
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        for (i in 1..999) {
            val name = if (extension.isEmpty()) "$base ($i)" else "$base ($i).$extension"
            val path = if (folder.isEmpty()) name else "$folder/$name"
            if (!SmbClient.fileExists(context, original.host, original.share, path)) return path
        }
        return null
    }
}
