package org.iurl.litegallery

import android.content.Context
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.InputStream

/**
 * Glide ModelLoader for loading images from remote shares. Intercepts String models
 * starting with a remote scheme (smb://, nfs://) and loads them via [RemoteClients].
 */
class RemoteModelLoader(private val context: Context) : ModelLoader<String, InputStream> {

    companion object {
        /** Prefix for thumbnail requests; they are served from the thumbnail cache on the share. */
        private const val SMB_THUMB_PREFIX = "smbthumb://"

        /** Check if a remote URL points to an image (not a video). */
        fun isRemoteImage(path: String): Boolean {
            val imagePath = if (path.startsWith(SMB_THUMB_PREFIX)) {
                path.removePrefix(SMB_THUMB_PREFIX)
            } else {
                path
            }

            if (!RemotePath.isRemote(imagePath)) return false
            if (RemoteFileTypes.isImage(imagePath)) return true

            // Thumbnails of SMB videos are made from a video frame (see SmbThumbnailCache).
            return path.startsWith(SMB_THUMB_PREFIX) &&
                SmbPath.isSmb(imagePath) &&
                RemoteFileTypes.isVideo(imagePath)
        }
    }

    override fun buildLoadData(
        model: String,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream>? {
        if (!isRemoteImage(model)) {
            return null // Only handle remote image paths; videos can't be decoded as images
        }
        return ModelLoader.LoadData(ObjectKey(model), RemoteImageFetcher(context, model))
    }

    override fun handles(model: String): Boolean = isRemoteImage(model)
}

/**
 * Glide DataFetcher that reads image data from remote shares.
 */
class RemoteImageFetcher(
    private val context: Context,
    private val url: String
) : DataFetcher<InputStream> {

    private var inputStream: InputStream? = null

    @Volatile
    private var cancelled = false

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        // The picture scrolled out of view while waiting for a free thread: do not load it.
        if (cancelled) {
            callback.onLoadFailed(java.io.IOException("Cancelled"))
            return
        }
        try {
            val isThumbnail = url.startsWith("smbthumb://")
            val imageUrl = if (isThumbnail) url.removePrefix("smbthumb://") else url

            val client = RemoteClients.forUrl(imageUrl)
            if (client == null) {
                callback.onLoadFailed(Exception("No remote client for URL: $imageUrl"))
                return
            }

            val stream = if (isThumbnail && SmbPath.isSmb(imageUrl)) {
                try {
                    SmbThumbnailCache.openThumbnailStream(context, imageUrl)
                } catch (e: Exception) {
                    android.util.Log.w("RemoteModelLoader", "Thumbnail cache failed for $imageUrl", e)
                    // Never download a whole video just to show a thumbnail.
                    if (RemoteFileTypes.isVideo(imageUrl)) throw e
                    client.openInputStream(context, imageUrl)
                }
            } else {
                client.openInputStream(context, imageUrl)
            }

            inputStream = stream
            callback.onDataReady(stream)
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        inputStream = null
    }

    override fun cancel() {
        // A read that already started cannot be interrupted, but one that has not is skipped.
        cancelled = true
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.REMOTE
}

/**
 * Factory for creating RemoteModelLoader instances.
 * Registered with Glide by MediaSourceBootstrap.
 */
class RemoteModelLoaderFactory(private val context: Context) :
    ModelLoaderFactory<String, InputStream> {

    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<String, InputStream> {
        return RemoteModelLoader(context)
    }

    override fun teardown() {
        // No-op
    }
}
