package org.iurl.litegallery

import android.app.Application
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory

class LiteGalleryApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Apply locale before theme (order matters)
        LocaleHelper.applyLocale(this)

        // First-launch / upgrade: ensure a pack pref exists (default Warm Paper)
        ThemeHelper.migrateLegacyIfNeeded(this)

        // Apply theme when app starts (consults the active pack to decide night mode)
        ThemeHelper.applyTheme(this)

        // Glide's cache on the phone must be set up before anything loads a picture.
        setupImageCache()

        // Register any optional media sources for the current flavor (no-op in `core`).
        MediaSourceBootstrap.init(this)

        // Remove leftovers of shares and edits that were never cleaned up.
        TempFileCleaner.cleanUpInBackground(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        // Release any media-source resources (e.g. SMB connections).
        MediaSourceRegistry.all().forEach { it.shutdown() }
    }

    /**
     * Thumbnails from network shares are kept on the phone (see MediaThumbnails), so folders seen
     * once open quickly later, also over a slow VPN. The default of 250 MB holds only a few
     * thousand of them; 1 GB holds the thumbnails of a large photo collection.
     */
    private fun setupImageCache() {
        try {
            Glide.init(
                this,
                GlideBuilder().setDiskCache(InternalCacheDiskCacheFactory(this, IMAGE_CACHE_BYTES))
            )
        } catch (e: Exception) {
            android.util.Log.w("LiteGalleryApplication", "Glide keeps its default cache size", e)
        }
    }

    private companion object {
        const val IMAGE_CACHE_BYTES = 1024L * 1024L * 1024L
    }
}
