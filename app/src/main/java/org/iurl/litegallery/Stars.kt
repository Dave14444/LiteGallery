package org.iurl.litegallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pictures and videos marked with a star.
 *
 * Kept per folder (see FolderData): for a folder on the NAS on the NAS itself, so the phone and
 * the tablet show the same stars. A change reads the latest state first and changes only the
 * files concerned, so stars set on another device meanwhile are kept.
 */
object Stars {

    private const val KEY = "stars"

    /** Folder → names of its files with a star, as last read or written. */
    private val byFolder = HashMap<String, Set<String>>()

    /** Counts the changes in this run of the app: screens compare it to know when to redraw. */
    @Volatile
    var changes = 0
        private set

    private fun folderOf(path: String): String = FolderData.normalize(path.substringBeforeLast('/', ""))

    private fun nameOf(path: String): String = path.substringAfterLast('/')

    /** Whether [path] has a star (as far as its folder was read; quick, nothing is asked). */
    fun isStarred(path: String): Boolean {
        val names = synchronized(byFolder) { byFolder[folderOf(path)] } ?: return false
        return nameOf(path) in names
    }

    /** The names with a star in [folderPath], as far as it was read. */
    fun starredIn(folderPath: String): Set<String> =
        synchronized(byFolder) { byFolder[FolderData.normalize(folderPath)] }.orEmpty()

    fun isLoaded(folderPath: String): Boolean =
        synchronized(byFolder) { FolderData.normalize(folderPath) in byFolder }

    /**
     * Reads the stars of [folderPath] (blocking: on the NAS one small file). Returns the names
     * with a star; if they cannot be read, what was known before.
     */
    fun load(context: Context, folderPath: String): Set<String> {
        val folder = FolderData.normalize(folderPath)
        val names = try {
            parse(FolderData.read(context, folder, KEY))
        } catch (e: Exception) {
            android.util.Log.w("Stars", "Stars of $folder not readable", e)
            return starredIn(folder)
        }
        val changed = synchronized(byFolder) {
            val before = byFolder.put(folder, names)
            before != names
        }
        if (changed) changes++
        return names
    }

    /**
     * Gives [paths] a star or takes it away (blocking). Throws if the stars cannot be stored
     * (e.g. no connection to the NAS): then nothing changed.
     */
    fun set(context: Context, paths: Collection<String>, starred: Boolean) {
        for ((folder, inFolder) in paths.groupBy { folderOf(it) }) {
            // The latest state, with what another device changed meanwhile.
            val current = parse(FolderData.read(context, folder, KEY))
            val names = inFolder.map { nameOf(it) }
            val updated = if (starred) current + names else current - names.toSet()
            if (updated != current) FolderData.write(context, folder, KEY, format(updated))
            synchronized(byFolder) { byFolder[folder] = updated }
        }
        changes++
    }

    /** After a file was renamed or moved within the app: the star goes along. */
    fun moved(context: Context, from: String, to: String) {
        if (!isStarred(from)) return
        try {
            set(context, listOf(from), starred = false)
            set(context, listOf(to), starred = true)
        } catch (e: Exception) {
            android.util.Log.w("Stars", "Star of $from not moved", e)
        }
    }

    internal fun parse(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        val array = JSONObject(json).optJSONArray("starred") ?: return emptySet()
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    internal fun format(names: Set<String>): String =
        JSONObject()
            .put("version", 1)
            .put("starred", JSONArray(names.sorted()))
            .toString()

    /** For tests: as after a fresh start of the app. */
    internal fun forgetAll() {
        synchronized(byFolder) { byFolder.clear() }
    }
}
