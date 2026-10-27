package org.iurl.litegallery

import android.graphics.drawable.Drawable
import com.bumptech.glide.ListPreloader
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey

/**
 * The thumbnail request for a media item. Every list and the preloader use exactly this, so a
 * thumbnail loaded (or preloaded) once is found in Glide's caches by all of them.
 */
object MediaThumbnails {

    fun request(requestManager: RequestManager, skeleton: MediaItemSkeleton): RequestBuilder<Drawable> =
        requestManager
            .load(skeleton.thumbnailModel())
            // A changed file (edited, replaced) gets a new thumbnail.
            .signature(ObjectKey("${skeleton.path}_${skeleton.dateModified}_${skeleton.size}"))
            .diskCacheStrategy(diskCacheStrategy(skeleton))
            .centerCrop()

    private fun diskCacheStrategy(skeleton: MediaItemSkeleton): DiskCacheStrategy = when {
        // The 384 px thumbnail from the share is small: keep it on the phone, so scrolling back
        // does not read it over the network again.
        skeleton.path.startsWith("smb://") -> DiskCacheStrategy.DATA
        skeleton.isRemote -> DiskCacheStrategy.RESOURCE
        else -> DiskCacheStrategy.AUTOMATIC
    }

    /**
     * Loads the thumbnails of the next items ahead of scrolling. Only thumbnails from a network
     * share are preloaded: they are kept on the phone (DATA cache), so the list finds them there
     * however big it shows them. Pictures on the phone load quickly anyway.
     */
    fun preloader(
        requestManager: RequestManager,
        density: Float,
        itemAt: (position: Int) -> MediaItemSkeleton?
    ): RecyclerViewPreloader<MediaItemSkeleton> {
        val modelProvider = object : ListPreloader.PreloadModelProvider<MediaItemSkeleton> {
            override fun getPreloadItems(position: Int): MutableList<MediaItemSkeleton> {
                val item = itemAt(position)
                return if (item != null && item.path.startsWith("smb://") && !item.usesPlaceholderThumbnail) {
                    mutableListOf(item)
                } else {
                    mutableListOf()
                }
            }

            override fun getPreloadRequestBuilder(item: MediaItemSkeleton): RequestBuilder<Drawable> =
                request(requestManager, item)
        }
        val sizePx = (PRELOAD_SIZE_DP * density).toInt().coerceAtLeast(PRELOAD_SIZE_DP)
        val sizeProvider = ListPreloader.PreloadSizeProvider<MediaItemSkeleton> { _, _, _ ->
            intArrayOf(sizePx, sizePx)
        }
        return RecyclerViewPreloader(requestManager, modelProvider, sizeProvider, PRELOAD_AHEAD)
    }

    private const val PRELOAD_SIZE_DP = 96
    private const val PRELOAD_AHEAD = 20
}
