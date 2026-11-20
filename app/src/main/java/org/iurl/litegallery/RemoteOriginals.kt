package org.iurl.litegallery

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.load.Key
import com.bumptech.glide.signature.ObjectKey
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The original of a picture from a network share, as it was read to show it.
 *
 * The viewer reads a picture from the share completely, and the image cache keeps the bytes as
 * they arrived. Zooming, sharing and editing take the original from there instead of reading it
 * from the share once more (over the VPN that takes a while).
 */
object RemoteOriginals {

    /** How long looking into the cache may take. */
    private const val LOOKUP_TIMEOUT_SECONDS = 5L

    /** Tells versions of a file apart in the image cache; the viewer loads pictures with it. */
    fun signature(path: String, dateModified: Long, size: Long): Key = ObjectKey("${path}_${dateModified}_${size}")

    fun signature(item: MediaItem): Key = signature(item.path, item.dateModified, item.size)

    /**
     * The original of the picture at [path] (in the version [dateModified] / [size] as listed)
     * from the image cache, or null if it is not there (not shown yet, or the cache was full).
     * Blocks briefly: not on the main thread. The file belongs to the cache: copy it, do not hand
     * it out or change it.
     */
    fun cached(context: Context, path: String, dateModified: Long, size: Long): File? = try {
        Glide.with(context.applicationContext)
            .downloadOnly()
            .load(path)
            .signature(signature(path, dateModified, size))
            .onlyRetrieveFromCache(true)
            .submit()
            .get(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?.takeIf { isComplete(it, size) }
    } catch (e: Exception) {
        null
    }

    fun cached(context: Context, item: MediaItem): File? =
        if (item.isRemote && !item.isVideo) cached(context, item.path, item.dateModified, item.size) else null

    /** A cached original is used only if it is the whole file (as big as listed, when known). */
    internal fun isComplete(file: File, listedSize: Long): Boolean =
        file.isFile && file.length() > 0L && (listedSize <= 0L || file.length() == listedSize)
}
