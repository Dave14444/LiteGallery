package org.iurl.litegallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Camera RAW files of a folder on a network share, kept on the device for a while.
 *
 * Over the VPN a RAW file (25–30 MB) takes half a minute to arrive. Fetched at home in advance
 * (folder menu "Load RAWs onto the device"), "Open RAW" in the viewer hands over the copy on the
 * device at once, without asking the share. The copies of a folder are removed [KEEP_DAYS] days
 * after they were last fetched.
 *
 * On the device: one directory per picture folder (named after a hash of its path), holding the
 * RAW files and an index (which picture each belongs to, where it is on the share).
 */
object RawOffline {

    const val KEEP_DAYS = 7
    const val KEEP_MS = KEEP_DAYS * 24L * 60L * 60L * 1000L

    private const val DIRECTORY = "raw_offline"
    private const val INDEX = "index.json"
    private const val PART_SUFFIX = ".part"

    /** A RAW file on the device: [picture] is the name of its picture without extension. */
    data class Kept(val picture: String, val rawPath: String, val name: String, val size: Long)

    /** What is kept for one picture folder, fetched last at [savedAt]. */
    data class Folder(val folderPath: String, val savedAt: Long, val files: List<Kept>) {
        val expiresAt: Long get() = savedAt + KEEP_MS
        val bytes: Long get() = files.sumOf { it.size.coerceAtLeast(0L) }
    }

    /** The copy of a RAW file found for a picture. */
    data class Hit(val rawPath: String, val name: String, val file: File)

    private val lock = Any()

    // ---------------------------------------------------------------- where

    /** All kept RAW files. Not shown by galleries (.nomedia), removed with the app. */
    fun root(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIRECTORY).also { root ->
            if (!root.isDirectory) root.mkdirs()
            val noMedia = File(root, ".nomedia")
            if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
        }
    }

    fun folderDir(context: Context, folderPath: String): File = File(root(context), key(folderPath))

    /** The file a RAW file is kept in, and the one it is written to while it arrives. */
    fun fileFor(context: Context, folderPath: String, name: String): File = File(folderDir(context, folderPath), name)

    fun partFileFor(context: Context, folderPath: String, name: String): File =
        File(folderDir(context, folderPath), name + PART_SUFFIX)

    internal fun key(folderPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(folderPath.trimEnd('/').toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- reading

    /** What is kept for [folderPath] (also if it expired meanwhile), or null. */
    fun read(context: Context, folderPath: String): Folder? = synchronized(lock) {
        readIndex(folderDir(context, folderPath))
    }

    fun isExpired(folder: Folder, now: Long = System.currentTimeMillis()): Boolean = now >= folder.expiresAt

    /**
     * The copy of the RAW file of the picture at [picturePath] (named [pictureName]), or null if
     * there is none (or it expired). Quick: nothing is asked from the share.
     */
    fun lookup(context: Context, picturePath: String, pictureName: String, now: Long = System.currentTimeMillis()): Hit? {
        val source = MediaSourceRegistry.forPath(picturePath) ?: return null
        val folderPath = source.parentFolder(picturePath) ?: return null
        return lookupIn(folderDir(context, folderPath), pictureName, now)
    }

    internal fun lookupIn(dir: File, pictureName: String, now: Long): Hit? {
        val folder = synchronized(lock) { readIndex(dir) } ?: return null
        if (isExpired(folder, now)) return null
        val baseName = pictureName.substringBeforeLast('.')
        val kept = folder.files.firstOrNull { it.picture.equals(baseName, ignoreCase = true) } ?: return null
        val file = File(dir, kept.name)
        val complete = file.isFile && (kept.size <= 0L || file.length() == kept.size)
        return if (complete) Hit(kept.rawPath, kept.name, file) else null
    }

    /** Whether the RAW file [rawPath] (of [size] bytes) is on the device already for [folderPath]. */
    fun has(context: Context, folderPath: String, rawPath: String, size: Long): Boolean {
        val folder = read(context, folderPath) ?: return false
        if (isExpired(folder)) return false
        val kept = folder.files.firstOrNull { it.rawPath == rawPath } ?: return false
        val file = fileFor(context, folderPath, kept.name)
        return file.isFile && (size <= 0L || file.length() == size)
    }

    // ---------------------------------------------------------------- writing

    /**
     * Records [kept] (already in place, see [fileFor]) for [folderPath] and starts its
     * [KEEP_DAYS] anew from [now]. Earlier entries of the same pictures are replaced.
     */
    fun record(context: Context, folderPath: String, kept: List<Kept>, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val dir = folderDir(context, folderPath)
            dir.mkdirs()
            val earlier = readIndex(dir)?.takeUnless { isExpired(it, now) }?.files.orEmpty()
            val pictures = kept.map { it.picture.lowercase() }.toSet()
            val files = earlier.filter { it.picture.lowercase() !in pictures } + kept
            writeIndex(dir, Folder(folderPath.trimEnd('/'), now, files))
        }
    }

    /** Removes everything kept for [folderPath]. */
    fun remove(context: Context, folderPath: String) {
        synchronized(lock) { folderDir(context, folderPath).deleteRecursively() }
    }

    /** Removes what expired (and what was left half done for as long). Returns how many folders. */
    fun removeExpired(context: Context, now: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        val dirs = root(context).listFiles { file -> file.isDirectory }.orEmpty()
        var removed = 0
        for (dir in dirs) {
            val folder = readIndex(dir)
            val gone = if (folder != null) isExpired(folder, now) else now - dir.lastModified() >= KEEP_MS
            if (gone && dir.deleteRecursively()) removed++
        }
        removed
    }

    /** Bytes taken by all kept RAW files. */
    fun usedBytes(context: Context): Long =
        root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---------------------------------------------------------------- the index

    internal fun readIndex(dir: File): Folder? {
        val file = File(dir, INDEX)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val files = json.optJSONArray("files") ?: JSONArray()
            Folder(
                folderPath = json.getString("folder"),
                savedAt = json.getLong("savedAt"),
                files = (0 until files.length()).map { i ->
                    val entry = files.getJSONObject(i)
                    Kept(
                        picture = entry.getString("picture"),
                        rawPath = entry.getString("raw"),
                        name = entry.getString("name"),
                        size = entry.optLong("size", -1L)
                    )
                }
            )
        } catch (e: Exception) {
            android.util.Log.w("RawOffline", "Unreadable index in $dir", e)
            null
        }
    }

    /** Written to a new file first: a reader never sees a half written index. */
    internal fun writeIndex(dir: File, folder: Folder) {
        val files = JSONArray()
        folder.files.forEach { kept ->
            files.put(
                JSONObject()
                    .put("picture", kept.picture)
                    .put("raw", kept.rawPath)
                    .put("name", kept.name)
                    .put("size", kept.size)
            )
        }
        val json = JSONObject()
            .put("folder", folder.folderPath)
            .put("savedAt", folder.savedAt)
            .put("files", files)
        val temp = File(dir, "$INDEX.new")
        temp.writeText(json.toString())
        if (!temp.renameTo(File(dir, INDEX))) {
            File(dir, INDEX).delete()
            temp.renameTo(File(dir, INDEX))
        }
    }
}
