package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FileTransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var fake: FakeMediaSource
    private val originalScanner = FileTransfer.localScanner

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FileTransfer.localScanner = { _, _ -> }
        OperationCancel.reset()
        fake = FakeMediaSource()
        MediaSourceRegistry.register(fake)
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        (MediaSourceRegistry.all() as MutableList<MediaSource>).remove(fake)
        FileTransfer.localScanner = originalScanner
        OperationCancel.reset()
    }

    private fun localFile(name: String, content: ByteArray, dir: File = tmp.root): File =
        File(dir, name).also { it.writeBytes(content) }

    private fun doTransfer(
        sources: List<String>,
        dest: String,
        mode: FileTransfer.Mode,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = FileTransfer.transfer(context, sources, dest, mode, onProgress)

    // ---------------------------------------------------------------- uniqueFile

    @Test
    fun uniqueFile_keepsFreeName() {
        assertEquals("a.jpg", FileTransfer.uniqueFile(tmp.root, "a.jpg").name)
    }

    @Test
    fun uniqueFile_countsUpForTakenNames() {
        localFile("a.jpg", byteArrayOf(1))
        assertEquals("a (1).jpg", FileTransfer.uniqueFile(tmp.root, "a.jpg").name)
        localFile("a (1).jpg", byteArrayOf(1))
        assertEquals("a (2).jpg", FileTransfer.uniqueFile(tmp.root, "a.jpg").name)
    }

    @Test
    fun uniqueFile_worksWithoutExtension() {
        localFile("readme", byteArrayOf(1))
        assertEquals("readme (1)", FileTransfer.uniqueFile(tmp.root, "readme").name)
    }

    // ---------------------------------------------------------------- sizeMatches

    @Test
    fun sizeMatches_equalSizesAreFine() {
        assertTrue(FileTransfer.sizeMatches(100, 100, FileTransfer.Mode.MOVE))
        assertTrue(FileTransfer.sizeMatches(100, 100, FileTransfer.Mode.COPY))
    }

    @Test
    fun sizeMatches_differentSizesNeverMatch() {
        assertFalse(FileTransfer.sizeMatches(100, 50, FileTransfer.Mode.MOVE))
        assertFalse(FileTransfer.sizeMatches(100, 50, FileTransfer.Mode.COPY))
    }

    @Test
    fun sizeMatches_unknownSizeIsOnlyAcceptedWhenCopying() {
        assertTrue(FileTransfer.sizeMatches(-1, 100, FileTransfer.Mode.COPY))
        assertTrue(FileTransfer.sizeMatches(100, -1, FileTransfer.Mode.COPY))
        assertFalse(FileTransfer.sizeMatches(-1, 100, FileTransfer.Mode.MOVE))
        assertFalse(FileTransfer.sizeMatches(100, -1, FileTransfer.Mode.MOVE))
    }

    // ---------------------------------------------------------------- copy and move

    @Test
    fun copyLocalToLocal_keepsOriginalAndNamesDuplicatesUniquely() {
        val data = ByteArray(1000) { it.toByte() }
        val source = localFile("a.jpg", data)
        val destDir = tmp.newFolder("dest")

        val first = doTransfer(listOf(source.absolutePath), destDir.absolutePath, FileTransfer.Mode.COPY)
        val second = doTransfer(listOf(source.absolutePath), destDir.absolutePath, FileTransfer.Mode.COPY)

        assertEquals(1, first.done)
        assertEquals(1, second.done)
        assertEquals(0, second.failed)
        assertTrue(source.exists())
        assertArrayEquals(data, File(destDir, "a.jpg").readBytes())
        assertArrayEquals(data, File(destDir, "a (1).jpg").readBytes())
    }

    @Test
    fun moveToShare_deletesOriginalAfterCheckedCopy() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        val source = localFile("a.jpg", data)

        val result = doTransfer(listOf(source.absolutePath), "fake://share/Photos", FileTransfer.Mode.MOVE)

        assertEquals(1, result.done)
        assertEquals(0, result.failed)
        assertFalse(source.exists())
        assertArrayEquals(data, fake.files["fake://share/Photos/a.jpg"])
        assertEquals(listOf(source.absolutePath), result.removedPaths)
        assertEquals(listOf("fake://share/Photos/a.jpg"), result.createdPaths)
    }

    @Test
    fun moveToShare_keepsOriginalAndRemovesBrokenCopyOnSizeMismatch() {
        fake.truncateWrites = true
        val source = localFile("a.jpg", ByteArray(5000) { 7 })

        val result = doTransfer(listOf(source.absolutePath), "fake://share/Photos", FileTransfer.Mode.MOVE)

        assertEquals(0, result.done)
        assertEquals(1, result.failed)
        assertTrue("The original must survive a bad copy", source.exists())
        assertEquals(5000L, source.length())
        assertTrue("The broken copy must be removed", fake.files.isEmpty())
        assertEquals(FileTransfer.Reason.SIZE_MISMATCH, result.failures.single().reason)
        assertEquals(source.absolutePath, result.failures.single().path)
        assertTrue(result.removedPaths.isEmpty())
        assertTrue(result.createdPaths.isEmpty())
    }

    @Test
    fun copyToShare_alsoRejectsTruncatedCopy() {
        fake.truncateWrites = true
        val source = localFile("a.jpg", ByteArray(100) { 1 })

        val result = doTransfer(listOf(source.absolutePath), "fake://share/Photos", FileTransfer.Mode.COPY)

        assertEquals(1, result.failed)
        assertTrue(fake.files.isEmpty())
        assertTrue(source.exists())
    }

    @Test
    fun moveToShare_keepsOriginalIfSizeCannotBeChecked() {
        fake.sizeUnknown = true
        val source = localFile("a.jpg", ByteArray(100) { 1 })

        val result = doTransfer(listOf(source.absolutePath), "fake://share/Photos", FileTransfer.Mode.MOVE)

        assertEquals(0, result.done)
        assertEquals(FileTransfer.Reason.SIZE_MISMATCH, result.failures.single().reason)
        assertTrue(source.exists())
        assertTrue(fake.files.isEmpty())
    }

    @Test
    fun copyToShare_acceptsCopyIfSizeCannotBeChecked() {
        fake.sizeUnknown = true
        val source = localFile("a.jpg", ByteArray(100) { 1 })

        val result = doTransfer(listOf(source.absolutePath), "fake://share/Photos", FileTransfer.Mode.COPY)

        assertEquals(1, result.done)
        assertEquals(1, fake.files.size)
        assertTrue(source.exists())
    }

    @Test
    fun moveFromShare_reportsOriginalThatCannotBeDeleted() {
        val data = ByteArray(300) { 3 }
        fake.files["fake://share/Photos/a.jpg"] = data
        fake.failDelete = true
        val destDir = tmp.newFolder("phone")

        val result = doTransfer(listOf("fake://share/Photos/a.jpg"), destDir.absolutePath, FileTransfer.Mode.MOVE)

        assertEquals(0, result.done)
        assertEquals(1, result.failed)
        assertEquals(FileTransfer.Reason.DELETE_FAILED, result.failures.single().reason)
        // The copy is complete, so it stays; the original on the share was not touched.
        assertArrayEquals(data, File(destDir, "a.jpg").readBytes())
        assertTrue(fake.files.containsKey("fake://share/Photos/a.jpg"))
    }

    @Test
    fun moveFromShare_toPhoneWorksAndRemovesOriginal() {
        val data = ByteArray(300) { 9 }
        fake.files["fake://share/Photos/a.jpg"] = data
        val destDir = tmp.newFolder("phone")

        val result = doTransfer(listOf("fake://share/Photos/a.jpg"), destDir.absolutePath, FileTransfer.Mode.MOVE)

        assertEquals(1, result.done)
        assertArrayEquals(data, File(destDir, "a.jpg").readBytes())
        assertTrue(fake.files.isEmpty())
    }

    @Test
    fun missingSourceIsReportedAsFailureWithAReason() {
        val result = doTransfer(
            listOf(File(tmp.root, "gone.jpg").absolutePath),
            "fake://share/Photos",
            FileTransfer.Mode.MOVE
        )

        assertEquals(0, result.done)
        assertEquals(1, result.failed)
        assertEquals(1, result.failures.size)
    }

    @Test
    fun oneBadFileDoesNotStopTheOthers() {
        val good = localFile("good.jpg", ByteArray(10) { 1 })
        val missing = File(tmp.root, "missing.jpg")
        val good2 = localFile("good2.jpg", ByteArray(10) { 2 })

        val result = doTransfer(
            listOf(good.absolutePath, missing.absolutePath, good2.absolutePath),
            "fake://share/Photos",
            FileTransfer.Mode.COPY
        )

        assertEquals(2, result.done)
        assertEquals(1, result.failed)
        assertFalse(result.cancelled)
        assertEquals(2, fake.files.size)
    }

    // ---------------------------------------------------------------- cancel

    @Test
    fun cancelBeforeStart_processesNothing() {
        val source = localFile("a.jpg", ByteArray(10) { 1 })
        OperationCancel.request()

        val result = doTransfer(listOf(source.absolutePath), "fake://share/Photos", FileTransfer.Mode.MOVE)

        assertTrue(result.cancelled)
        assertEquals(0, result.done)
        assertEquals(0, result.failed)
        assertTrue(source.exists())
        assertTrue(fake.files.isEmpty())
    }

    @Test
    fun cancelBetweenFiles_stopsAfterTheCurrentOne() {
        val a = localFile("a.jpg", ByteArray(10) { 1 })
        val b = localFile("b.jpg", ByteArray(10) { 2 })
        val c = localFile("c.jpg", ByteArray(10) { 3 })

        val result = doTransfer(
            listOf(a.absolutePath, b.absolutePath, c.absolutePath),
            "fake://share/Photos",
            FileTransfer.Mode.MOVE
        ) { done, _ -> if (done == 1) OperationCancel.request() }

        assertTrue(result.cancelled)
        assertEquals(1, result.done)
        assertEquals(0, result.failed)
        assertFalse(a.exists())
        assertTrue(b.exists())
        assertTrue(c.exists())
        assertEquals(listOf("fake://share/Photos/a.jpg"), fake.files.keys.toList())
    }

    @Test
    fun cancelWhileCopying_leavesOriginalAndIsNoFailure() {
        val source = localFile("a.jpg", ByteArray(10) { 1 })
        // The request arrives while the file is being copied.
        fake.beforeWrite = { OperationCancel.request() }

        val result = doTransfer(listOf(source.absolutePath), "fake://share/Photos", FileTransfer.Mode.MOVE)

        assertTrue(result.cancelled)
        assertEquals(0, result.done)
        assertEquals(0, result.failed)
        assertTrue(result.failures.isEmpty())
        assertTrue(source.exists())
        assertTrue(fake.files.isEmpty())
    }

    @Test
    fun cancellableStream_failsOnReadAfterRequest() {
        val source = localFile("a.jpg", ByteArray(10) { 1 })
        OperationCancel.request()
        val stream = CancellableInputStream(source.inputStream())

        var thrown = false
        try {
            stream.read(ByteArray(4))
        } catch (e: OperationCancelledException) {
            thrown = true
        }
        stream.close()

        assertTrue(thrown)
        assertTrue(source.exists())
    }

    @Test
    fun streamReadsNormallyWithoutCancelRequest() {
        val data = byteArrayOf(1, 2, 3, 4)
        val stream = CancellableInputStream(data.inputStream())

        assertArrayEquals(data, stream.readBytes())
    }

    // ---------------------------------------------------------------- delete

    private fun skeleton(path: String) = MediaItemSkeleton(
        id = MediaItem.NO_MEDIASTORE_ID,
        path = path,
        name = path.substringAfterLast('/'),
        dateModified = 0L,
        size = 0L,
        isVideo = false
    )

    @Test
    fun delete_removesLocalFilesForGood() {
        val a = localFile("a.jpg", byteArrayOf(1))
        val b = localFile("b.jpg", byteArrayOf(2))

        val result = FileTransfer.delete(
            context, listOf(skeleton(a.absolutePath), skeleton(b.absolutePath)),
            moveToTrash = false, onProgress = { _, _ -> }
        )

        assertEquals(2, result.done)
        assertEquals(0, result.failed)
        assertFalse(a.exists())
        assertFalse(b.exists())
    }

    @Test
    fun delete_listsFilesThatCouldNotBeDeleted() {
        fake.files["fake://share/a.jpg"] = byteArrayOf(1)
        fake.failDelete = true

        val result = FileTransfer.delete(
            context, listOf(skeleton("fake://share/a.jpg")),
            moveToTrash = false, onProgress = { _, _ -> }
        )

        assertEquals(0, result.done)
        assertEquals(1, result.failed)
        assertEquals("fake://share/a.jpg", result.failures.single().path)
        assertEquals(FileTransfer.Reason.DELETE_FAILED, result.failures.single().reason)
        assertTrue(fake.files.containsKey("fake://share/a.jpg"))
    }

    @Test
    fun delete_canBeCancelled() {
        val a = localFile("a.jpg", byteArrayOf(1))
        val b = localFile("b.jpg", byteArrayOf(2))

        val result = FileTransfer.delete(
            context, listOf(skeleton(a.absolutePath), skeleton(b.absolutePath)),
            moveToTrash = false
        ) { done, _ -> if (done == 1) OperationCancel.request() }

        assertTrue(result.cancelled)
        assertEquals(1, result.done)
        assertFalse(a.exists())
        assertTrue(b.exists())
    }
}
