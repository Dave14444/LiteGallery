package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.iurl.litegallery.RawOffline.Kept
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RawOfflineTest {

    private lateinit var context: Context
    private lateinit var fake: FakeMediaSource

    private val folder = "fake://nas/Bilder/Urlaub"
    private val day = 24L * 60L * 60L * 1000L
    private val start = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fake = FakeMediaSource()
        MediaSourceRegistry.register(fake)
        RawOffline.root(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        (MediaSourceRegistry.all() as MutableList<MediaSource>).remove(fake)
        RawOffline.root(context).deleteRecursively()
    }

    /** Puts a RAW file onto the device as the loading does, and records it. */
    private fun keep(picture: String, name: String = "$picture.CR2", size: Int = 1000, at: Long = start): Kept {
        val kept = Kept(picture, "$folder/RAW/$name", name, size.toLong())
        RawOffline.fileFor(context, folder, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(size))
        }
        RawOffline.record(context, folder, listOf(kept), now = at)
        return kept
    }

    @Test
    fun aKeptRawFile_isFoundForItsPicture() {
        keep("IMG_1234")

        val hit = RawOffline.lookup(context, "$folder/IMG_1234.JPG", "IMG_1234.JPG", now = start + day)

        assertNotNull(hit)
        assertEquals("$folder/RAW/IMG_1234.CR2", hit!!.rawPath)
        assertEquals(1000L, hit.file.length())
        // Upper or lower case does not matter.
        assertNotNull(RawOffline.lookup(context, "$folder/img_1234.jpg", "img_1234.jpg", now = start + day))
    }

    @Test
    fun otherPicturesAndOtherFolders_haveNoHit() {
        keep("IMG_1234")
        assertNull(RawOffline.lookup(context, "$folder/IMG_1235.JPG", "IMG_1235.JPG", now = start))
        assertNull(RawOffline.lookup(context, "fake://nas/Bilder/Anders/IMG_1234.JPG", "IMG_1234.JPG", now = start))
    }

    @Test
    fun afterSevenDays_theCopyIsNotUsedAndRemoved() {
        keep("IMG_1234")
        assertNotNull(RawOffline.lookup(context, "$folder/IMG_1234.JPG", "IMG_1234.JPG", now = start + 7 * day - 1))
        assertNull(RawOffline.lookup(context, "$folder/IMG_1234.JPG", "IMG_1234.JPG", now = start + 7 * day))

        assertEquals(0, RawOffline.removeExpired(context, now = start + 6 * day))
        assertTrue(RawOffline.folderDir(context, folder).isDirectory)
        assertEquals(1, RawOffline.removeExpired(context, now = start + 7 * day))
        assertFalse(RawOffline.folderDir(context, folder).exists())
    }

    @Test
    fun loadingAgain_startsTheSevenDaysAnew() {
        keep("IMG_1", at = start)
        keep("IMG_2", at = start + 5 * day)

        // IMG_1 was loaded first, but the folder was loaded again later: both stay.
        val now = start + 10 * day
        assertNotNull(RawOffline.lookup(context, "$folder/IMG_1.JPG", "IMG_1.JPG", now = now))
        assertNotNull(RawOffline.lookup(context, "$folder/IMG_2.JPG", "IMG_2.JPG", now = now))
        assertEquals(start + 12 * day, RawOffline.read(context, folder)!!.expiresAt)
    }

    @Test
    fun anIncompleteCopy_isNotUsed() {
        val kept = keep("IMG_9", size = 1000)
        RawOffline.fileFor(context, folder, kept.name).writeBytes(ByteArray(10))

        assertNull(RawOffline.lookup(context, "$folder/IMG_9.JPG", "IMG_9.JPG", now = start))
        assertFalse(RawOffline.has(context, folder, kept.rawPath, kept.size))
    }

    @Test
    fun aFolderCanBeRemoved() {
        keep("IMG_1")
        RawOffline.remove(context, folder)
        assertNull(RawOffline.read(context, folder))
        assertNull(RawOffline.lookup(context, "$folder/IMG_1.JPG", "IMG_1.JPG", now = start))
    }

    @Test
    fun theIndex_isReadBackAsWritten() {
        val dir = File(RawOffline.root(context), "test").apply { mkdirs() }
        val written = RawOffline.Folder(
            folder,
            start,
            listOf(Kept("IMG_1", "$folder/RAW/IMG_1.CR2", "IMG_1.CR2", 25_000_000L), Kept("IMG_2", "$folder/IMG_2.dng", "IMG_2.dng", -1L))
        )
        RawOffline.writeIndex(dir, written)
        assertEquals(written, RawOffline.readIndex(dir))
        assertEquals(25_000_000L, written.bytes)
    }

    @Test
    fun galleriesDoNotShowTheCopies() {
        assertTrue(File(RawOffline.root(context), ".nomedia").exists())
    }

    @Test
    fun foldersGetTheirOwnPlace() {
        assertEquals(RawOffline.key("fake://nas/a"), RawOffline.key("fake://nas/a/"))
        assertFalse(RawOffline.key("fake://nas/a") == RawOffline.key("fake://nas/b"))
    }
}
