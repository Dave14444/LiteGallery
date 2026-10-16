package org.iurl.litegallery

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers size and modification time of files seen in folder listings, so the thumbnail
 * cache does not have to ask the server for them once per image.
 */
object SmbFileMetaCache {

    data class Meta(val size: Long, val lastModified: Long)

    private const val MAX_ENTRIES = 100_000

    private val entries = ConcurrentHashMap<String, Meta>()

    fun put(url: String, size: Long, lastModified: Long) {
        if (entries.size >= MAX_ENTRIES) entries.clear()
        entries[url] = Meta(size, lastModified)
    }

    fun get(url: String): Meta? = entries[url]

    fun remove(url: String) {
        entries.remove(url)
    }
}
