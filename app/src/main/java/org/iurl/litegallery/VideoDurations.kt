package org.iurl.litegallery

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Lengths of videos for the duration label on the thumbnails. Videos on the phone are read from
 * the file; videos on a network share ask their [MediaSource], which remembers the length on
 * the share, so a video is opened only once.
 */
object VideoDurations {

    private const val MAX_ENTRIES = 2_000
    private const val MAX_PARALLEL = 3

    private val known = LruCache<String, Long>(MAX_ENTRIES)
    private val inProgress = HashSet<String>()
    private val failed = HashSet<String>()
    private val permits = Semaphore(MAX_PARALLEL)

    /** The label for a video: from the loaded metadata, or the length found by [load]. */
    @Synchronized
    fun text(skeleton: MediaItemSkeleton, mediaItem: MediaItem): String {
        val fromItem = mediaItem.getFormattedDuration()
        if (fromItem.isNotEmpty()) return fromItem
        return format(known.get(skeleton.path) ?: 0L)
    }

    /** True if [path] still has to be loaded (and marks it as being loaded). */
    @Synchronized
    fun beginLoading(path: String): Boolean {
        if (known.get(path) != null || failed.contains(path)) return false
        return inProgress.add(path)
    }

    /** Reads the length of the video at [path] in milliseconds (0 if it cannot be read). */
    suspend fun load(context: Context, path: String): Long {
        val milliseconds = try {
            permits.withPermit {
                withContext(Dispatchers.IO) { read(context, path) }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                synchronized(this) { inProgress.remove(path) }
                throw e
            }
            0L
        }

        synchronized(this) {
            inProgress.remove(path)
            if (milliseconds > 0L) known.put(path, milliseconds) else failed.add(path)
        }
        return milliseconds
    }

    private fun read(context: Context, path: String): Long {
        val source = MediaSourceRegistry.forPath(path)
        if (source != null) return source.videoDurationMs(context, path)

        val retriever = MediaMetadataRetriever()
        return try {
            if (path.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(path))
            } else {
                retriever.setDataSource(path)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun format(milliseconds: Long): String {
        if (milliseconds <= 0L) return ""
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format("%d:%02d", minutes, seconds % 60)
        }
    }
}
