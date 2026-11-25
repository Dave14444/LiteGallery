package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class StarsTest {

    private lateinit var context: Context
    private lateinit var fake: FakeMediaSource
    private val nas = "fake://nas/Bilder/Urlaub"
    private val phone = "/storage/emulated/0/DCIM/Camera"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fake = FakeMediaSource()
        MediaSourceRegistry.register(fake)
        Stars.forgetAll()
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        (MediaSourceRegistry.all() as MutableList<MediaSource>).remove(fake)
        Stars.forgetAll()
        java.io.File(context.filesDir, "folder_data").deleteRecursively()
    }

    @Test
    fun aStar_isKeptWithTheFolderOnTheNas() {
        Stars.set(context, listOf("$nas/IMG_1.JPG", "$nas/IMG_2.JPG"), starred = true)
        assertTrue(Stars.isStarred("$nas/IMG_1.JPG"))
        assertFalse(Stars.isStarred("$nas/IMG_3.JPG"))

        // Another device (or a new start) reads it from the NAS.
        Stars.forgetAll()
        assertFalse(Stars.isStarred("$nas/IMG_1.JPG"))
        assertEquals(setOf("IMG_1.JPG", "IMG_2.JPG"), Stars.load(context, nas))
        assertTrue(Stars.isStarred("$nas/IMG_2.JPG"))
    }

    @Test
    fun takingAStarAway_keepsTheOthers() {
        Stars.set(context, listOf("$nas/IMG_1.JPG", "$nas/IMG_2.JPG"), starred = true)
        Stars.set(context, listOf("$nas/IMG_1.JPG"), starred = false)
        assertEquals(setOf("IMG_2.JPG"), Stars.load(context, nas))
    }

    @Test
    fun starsSetOnAnotherDeviceMeanwhile_areKept() {
        Stars.set(context, listOf("$nas/IMG_1.JPG"), starred = true)
        // The tablet adds a star while this device still knows the old state.
        fake.folderData["$nas|stars"] = Stars.format(setOf("IMG_1.JPG", "IMG_9.JPG"))

        Stars.set(context, listOf("$nas/IMG_2.JPG"), starred = true)

        assertEquals(setOf("IMG_1.JPG", "IMG_2.JPG", "IMG_9.JPG"), Stars.load(context, nas))
    }

    @Test
    fun withoutConnection_nothingChangesAndTheErrorIsReported() {
        Stars.set(context, listOf("$nas/IMG_1.JPG"), starred = true)
        fake.folderDataUnreachable = true
        try {
            Stars.set(context, listOf("$nas/IMG_2.JPG"), starred = true)
            fail("expected an error")
        } catch (e: java.io.IOException) {
            // expected
        }
        assertFalse(Stars.isStarred("$nas/IMG_2.JPG"))
        // Reading keeps what was known.
        assertEquals(setOf("IMG_1.JPG"), Stars.load(context, nas))
    }

    @Test
    fun picturesOnThePhone_keepTheirStarsInTheApp() {
        Stars.set(context, listOf("$phone/PXL_1.jpg"), starred = true)
        Stars.forgetAll()
        assertEquals(setOf("PXL_1.jpg"), Stars.load(context, phone))
        assertTrue(Stars.isStarred("$phone/PXL_1.jpg"))
        assertEquals(0, fake.folderData.size)
    }

    @Test
    fun aRenamedPicture_keepsItsStar() {
        Stars.set(context, listOf("$nas/IMG_1.JPG"), starred = true)
        Stars.moved(context, "$nas/IMG_1.JPG", "$nas/Sonnenuntergang.JPG")
        assertFalse(Stars.isStarred("$nas/IMG_1.JPG"))
        assertTrue(Stars.isStarred("$nas/Sonnenuntergang.JPG"))
    }

    @Test
    fun everyChange_isCounted() {
        val before = Stars.changes
        Stars.set(context, listOf("$nas/IMG_1.JPG"), starred = true)
        assertTrue(Stars.changes > before)
    }
}
