package org.iurl.litegallery

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * The camera RAW file that belongs to a picture: the same name with another extension
 * (IMG_1234.JPG → IMG_1234.CR2), kept in a folder inside the picture's folder (whatever its name)
 * or right next to the picture.
 */
object RawCompanion {

    /** A file or folder as listed. */
    data class Entry(val name: String, val path: String, val isDirectory: Boolean)

    /** Folder contents are kept this long: the next pictures of the same folder are found at once. */
    private const val KEEP_MS = 5 * 60 * 1000L

    /** Folders whose contents are kept. */
    private const val MAX_FOLDERS = 24

    private class Listing(val entries: List<Entry>, val readAt: Long)

    private val listings = object : LinkedHashMap<String, Listing>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Listing>?) = size > MAX_FOLDERS
    }

    /**
     * Looks for the RAW file of the picture at [picturePath] (named [pictureName]). Blocks (on a
     * network share it reads folders): not on the main thread. Stops early once [keepGoing] says
     * so. Null if there is none.
     */
    fun find(context: Context, picturePath: String, pictureName: String, keepGoing: () -> Boolean = { true }): Entry? {
        val source = MediaSourceRegistry.forPath(picturePath)
        val folder: String
        val read: (String) -> List<Entry>
        if (source != null) {
            folder = source.parentFolder(picturePath) ?: return null
            read = { path -> source.listFolder(context, path).map { Entry(it.name, it.path, it.isDirectory) } }
        } else {
            // A picture handed over by another app (content://) has no folder to look in.
            if (picturePath.startsWith("content://")) return null
            folder = File(picturePath).parent ?: return null
            read = { path ->
                File(path).listFiles()?.map { Entry(it.name, it.path, it.isDirectory) } ?: emptyList()
            }
        }
        val found = search(pictureName, folder, keepGoing) { path -> listed(path, read) }
        // Not found: read the folders anew next time (a RAW file may have been added meanwhile).
        if (found == null) forgetBelow(folder)
        return found
    }

    /**
     * The search itself, apart from where the folders are (for tests): first next to the picture,
     * then in the folders inside its folder, those that sound like RAW files first.
     */
    internal fun search(
        pictureName: String,
        folderPath: String,
        keepGoing: () -> Boolean = { true },
        list: (String) -> List<Entry>
    ): Entry? {
        val baseName = pictureName.substringBeforeLast('.')
        if (baseName.isEmpty()) return null
        val inFolder = list(folderPath)
        pick(baseName, inFolder)?.let { return it }
        for (folder in searchOrder(inFolder)) {
            if (!keepGoing()) return null
            pick(baseName, list(folder.path))?.let { return it }
        }
        return null
    }

    /**
     * The folders to look in: those named like RAW files first ("RAW", "CR2", "Rohdaten"), then
     * the others by name. Hidden and system folders (".thumbnails", "@eaDir") are left out: they
     * can be big and never hold the photos.
     */
    internal fun searchOrder(entries: List<Entry>): List<Entry> =
        entries
            .filter { it.isDirectory && !it.name.startsWith('.') && !it.name.startsWith('@') }
            .sortedWith(compareBy({ if (soundsLikeRaw(it.name)) 0 else 1 }, { it.name.lowercase() }))

    private fun soundsLikeRaw(name: String): Boolean {
        val lower = name.lowercase()
        return RAW_FOLDER_WORDS.any { it in lower }
    }

    private val RAW_FOLDER_WORDS = listOf("raw", "roh", "cr2", "cr3", "nef", "arw", "dng")

    /** The RAW file named like the picture; the camera's own format before a DNG made from it. */
    internal fun pick(baseName: String, entries: List<Entry>): Entry? =
        entries
            .filter {
                !it.isDirectory &&
                    RawFileTypes.isRaw(it.name) &&
                    it.name.substringBeforeLast('.').equals(baseName, ignoreCase = true)
            }
            .minWithOrNull(compareBy({ RawFileTypes.extensionOf(it.name) == "dng" }, { it.name }))

    private fun listed(path: String, read: (String) -> List<Entry>): List<Entry> {
        val now = SystemClock.elapsedRealtime()
        synchronized(listings) {
            listings[path]?.takeIf { now - it.readAt < KEEP_MS }?.let { return it.entries }
        }
        val entries = read(path)
        synchronized(listings) { listings[path] = Listing(entries, now) }
        return entries
    }

    private fun forgetBelow(folder: String) {
        val prefix = folder.trimEnd('/', '\\') + "/"
        synchronized(listings) {
            listings.keys.removeAll { it == folder || it.startsWith(prefix) }
        }
    }

    /** For tests: nothing kept. */
    internal fun forgetAll() {
        synchronized(listings) { listings.clear() }
    }
}
