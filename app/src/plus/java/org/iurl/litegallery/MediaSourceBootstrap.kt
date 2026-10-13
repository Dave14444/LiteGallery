package org.iurl.litegallery

import android.app.Application
import com.bumptech.glide.Glide
import java.io.InputStream

/**
 * `plus` flavor: registers the network media source and remote-share Glide image loader.
 */
object MediaSourceBootstrap {
    fun init(app: Application) {
        android.util.Log.i(
    "SmbThumbDiag",
    "BOOTSTRAP: Plus media source initialization started"
)
        MediaSourceRegistry.register(NetworkMediaSource)

        // Allow Glide to load image thumbnails from remote shares (smb:// and nfs:// models).
        Glide.get(app).registry.prepend(
            String::class.java,
            InputStream::class.java,
            RemoteModelLoaderFactory(app)
        )
        android.util.Log.i("SmbThumbDiag", "BOOTSTRAP: RemoteModelLoader registered")
    }
}
