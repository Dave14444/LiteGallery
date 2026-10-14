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
        /** Check if a remote URL points to an image (not a video). */
        private const val SMB_THUMB_PREFIX = "smbthumb://"

fun isRemoteImage(path: String): Boolean {
    val imagePath = if (path.startsWith(SMB_THUMB_PREFIX)) {
        path.removePrefix(SMB_THUMB_PREFIX)
    } else {
        path
    }

    return RemotePath.isRemote(imagePath) &&
        RemoteFileTypes.isImage(imagePath)
}
    }

    override fun buildLoadData(
        model: String,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream>? {
        android.util.Log.i("SmbThumbDiag", "BUILD_LOAD_DATA: model=$model isImage=${isRemoteImage(model)}")
        if (!isRemoteImage(model)) {
            return null // Only handle remote image paths; videos can't be decoded as images
        }
        return ModelLoader.LoadData(ObjectKey(model), RemoteImageFetcher(context, model))
    }

    override fun handles(model: String): Boolean {
    val isRemote = model.startsWith("smb://") ||
        model.startsWith("smbthumb://") ||
        model.startsWith("nfs://")

    val result = isRemoteImage(model)

    
    if (isRemote) {
        android.util.Log.i(
            "SmbThumbDiag",
            "HANDLES: model=$model result=$result"
        )

        if (model.startsWith("smb://")) {
            android.util.Log.w(
                "SmbThumbDiag",
                "UNPREFIXED_SMB_MODEL: $model",
                Throwable("Call stack for normal smb:// model")
            )
        }
    }


    return result
}
}

/**
 * Glide DataFetcher that reads image data from remote shares.
 */
class RemoteImageFetcher(
    private val context: Context,
    private val url: String
) : DataFetcher<InputStream> {

    private var inputStream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        android.util.Log.i("SmbThumbDiag", "FETCH: url=$url")
        try {
            val isThumbnail = url.startsWith("smbthumb://")
val imageUrl = if (isThumbnail) {
    url.removePrefix("smbthumb://")
} else {
    url
}

val client = RemoteClients.forUrl(imageUrl)
if (client == null) {
    callback.onLoadFailed(
        Exception("No remote client for URL: $imageUrl")
    )
    return
}

val stream = if (isThumbnail && SmbPath.isSmb(imageUrl)) {
    android.util.Log.i("SmbThumbDiag", "CACHE_ROUTE: $imageUrl")
    SmbThumbnailCache.openThumbnailStream(context, imageUrl)
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
        // No-op: remote reads are synchronous
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
