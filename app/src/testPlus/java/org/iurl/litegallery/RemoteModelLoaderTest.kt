package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.load.Options
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RemoteModelLoaderTest {

    private lateinit var loader: RemoteModelLoader

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        loader = RemoteModelLoader(context)
    }

    @Test
    fun isRemoteImage_acceptsSmbAndNfsImageExtensions() {
        assertTrue(RemoteModelLoader.isRemoteImage("smb://host/share/photo.JPG"))
        assertTrue(RemoteModelLoader.isRemoteImage("smb://host/share/folder/image.heic"))
        assertTrue(RemoteModelLoader.isRemoteImage("nfs://host/volume1/photo/image.png"))
        assertFalse(RemoteModelLoader.isRemoteImage("smb://host/share/video.mp4"))
        assertFalse(RemoteModelLoader.isRemoteImage("nfs://host/volume1/video.mp4"))
        assertFalse(RemoteModelLoader.isRemoteImage("smb://host/share/no-extension"))
        assertFalse(RemoteModelLoader.isRemoteImage("/storage/emulated/0/DCIM/photo.jpg"))
    }

    @Test
    fun handles_returnsTrueOnlyForRemoteImages() {
        assertTrue(loader.handles("smb://host/share/photo.webp"))
        assertTrue(loader.handles("nfs://host/volume1/photo.webp"))
        assertFalse(loader.handles("smb://host/share/movie.mkv"))
        assertFalse(loader.handles("content://media/external/images/media/1"))
    }

    @Test
    fun buildLoadData_returnsFetcherOnlyForRemoteImages() {
        assertNotNull(loader.buildLoadData("smb://host/share/photo.png", 100, 100, Options()))
        assertNotNull(loader.buildLoadData("nfs://host/volume1/photo.png", 100, 100, Options()))
        assertNull(loader.buildLoadData("smb://host/share/movie.mp4", 100, 100, Options()))
        assertNull(loader.buildLoadData("/storage/emulated/0/DCIM/photo.png", 100, 100, Options()))
    }
}
