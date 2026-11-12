package org.iurl.litegallery

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority

/**
 * Fetches the first thumbnails of a folder onto the phone before the folder is opened: while
 * its card is shown in the folder above. Opened later, the folder shows its first pictures at
 * once instead of loading them over a slow connection (VPN) first.
 *
 * Only thumbnails that exist on the share are read (no picture is downloaded to make one).
 * Thumbnails on the phone already cost nothing; the rest is fetched after everything that is on
 * screen (low priority).
 */
object SmbThumbnailPrefetch {

    /** About the first screen of a folder. */
    private const val FIRST_PICTURES = 12

    /** Any size will do: the thumbnail is kept on the phone as it came from the share. */
    private const val SIZE_PX = 256

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fetches the thumbnails of the newest pictures and videos among [files] (the folder's
     * listing; newest first is how a folder opens).
     */
    fun prefetchFirst(context: Context, files: List<RemoteFileInfo>) {
        val first = files
            .filter { !it.isDirectory && it.isMedia && SmbPath.isSmb(it.url) }
            .sortedByDescending { it.lastModified }
            .take(FIRST_PICTURES)
        if (first.isEmpty()) return

        val appContext = context.applicationContext
        mainHandler.post {
            val requests = Glide.with(appContext)
            for (file in first) {
                // Exactly the request of the folder's list, so its cache entry is found there.
                val skeleton = MediaItemSkeleton(
                    id = MediaItem.NO_MEDIASTORE_ID,
                    path = file.url,
                    name = file.name,
                    dateModified = file.lastModified,
                    size = file.size,
                    isVideo = file.isVideo
                )
                MediaThumbnails.request(requests, skeleton)
                    .set(SmbThumbnailModel.ONLY_EXISTING, true)
                    .priority(Priority.LOW)
                    .preload(SIZE_PX, SIZE_PX)
            }
        }
    }
}
