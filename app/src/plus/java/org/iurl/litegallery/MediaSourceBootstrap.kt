package org.iurl.litegallery

import android.app.Application
import com.bumptech.glide.Glide
import java.io.InputStream

/**
 * `plus` flavor: registers the network media source and remote-share Glide image loader.
 */
object MediaSourceBootstrap {
    fun init(app: Application) {
        // Before anything connects: the rule for when the NAS may be contacted (home Wi-Fi or VPN).
        NetworkAccess.init(app)
        MediaSourceRegistry.register(NetworkMediaSource)

        // Allow Glide to load image thumbnails from network shares (smb:// models).
        Glide.get(app).registry.prepend(
            String::class.java,
            InputStream::class.java,
            RemoteModelLoaderFactory(app)
        )
    }
}
