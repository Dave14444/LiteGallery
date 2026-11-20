package org.iurl.litegallery

import androidx.appcompat.app.AppCompatActivity
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RemoteCopyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var fake: FakeMediaSource
    private lateinit var activity: AppCompatActivity

    private val path = "fake://nas/Urlaub/IMG_1234.JPG"
    private val content = ByteArray(300_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        fake = FakeMediaSource()
        fake.files[path] = content
        MediaSourceRegistry.register(fake)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_LiteGallery)
        activity = controller.setup().get()
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        (MediaSourceRegistry.all() as MutableList<MediaSource>).remove(fake)
    }

    /** Runs [RemoteCopy.fetch] and waits for its result (null if it gave none). */
    private fun fetch(target: File, cached: (() -> File?)?, knownModified: Long = 0L, beforeCopy: () -> Unit = {}): File? {
        var ready: File? = null
        var done = false
        RemoteCopy.fetch(
            activity, path, target, R.string.share_remote_failed,
            cached = cached, knownModified = knownModified, beforeCopy = beforeCopy
        ) {
            ready = it
            done = true
        }
        val until = System.currentTimeMillis() + 5_000
        while (!done && System.currentTimeMillis() < until) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        return ready
    }

    @Test
    fun aPictureThatWasShown_isTakenFromTheCacheAndNotLoadedAgain() {
        val inCache = File(temp.root, "glide-cache-entry").apply { writeBytes(content) }
        val target = File(temp.root, "share/1/IMG_1234.JPG")

        val file = fetch(target, cached = { inCache }, knownModified = 1_600_000_000_000L)

        assertNotNull(file)
        assertArrayEquals(content, target.readBytes())
        assertEquals(0, fake.inputsOpened)
        assertEquals(1_600_000_000_000L, target.lastModified())
        // The cache keeps its own file.
        assertTrue(inCache.isFile)
    }

    @Test
    fun aPictureNotInTheCache_isLoadedFromTheShare() {
        val target = File(temp.root, "share/2/IMG_1234.JPG")

        val file = fetch(target, cached = { null })

        assertNotNull(file)
        assertArrayEquals(content, target.readBytes())
        assertEquals(1, fake.inputsOpened)
    }

    @Test
    fun clearingOldCopies_happensBeforeTheCopy() {
        val old = File(temp.root, "edit/old.jpg").apply { parentFile?.mkdirs(); writeText("old") }
        val target = File(temp.root, "edit/IMG_1234.JPG")

        fetch(target, cached = null, beforeCopy = { File(temp.root, "edit").deleteRecursively() })

        assertFalse(old.exists())
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun aFileMissingOnTheShare_givesNoCopy() {
        fake.files.remove(path)
        val target = File(temp.root, "share/3/IMG_1234.JPG")

        var called = false
        RemoteCopy.fetch(activity, path, target, R.string.share_remote_failed) { called = true }
        val until = System.currentTimeMillis() + 2_000
        while (fake.inputsOpened == 0 && System.currentTimeMillis() < until) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        repeat(20) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertFalse(called)
        assertFalse(target.exists())
    }

    @Test
    fun onlyAWholeCachedFile_isUsed() {
        val file = File(temp.root, "entry").apply { writeBytes(ByteArray(100)) }
        assertTrue(RemoteOriginals.isComplete(file, 100L))
        assertTrue(RemoteOriginals.isComplete(file, 0L))
        assertFalse(RemoteOriginals.isComplete(file, 120L))
        assertFalse(RemoteOriginals.isComplete(File(temp.root, "missing"), 0L))
        // The viewer and the lookups use the same key for the same version of a file.
        assertEquals(RemoteOriginals.signature("a", 1L, 2L), RemoteOriginals.signature("a", 1L, 2L))
        assertFalse(RemoteOriginals.signature("a", 1L, 2L) == RemoteOriginals.signature("a", 1L, 3L))
    }
}
