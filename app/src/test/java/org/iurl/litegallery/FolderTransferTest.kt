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
class FolderTransferTest {

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
        OperationProgress.reset()
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

    private fun file(dir: File, relative: String, content: String = relative): File =
        File(dir, relative).also {
            it.parentFile?.mkdirs()
            it.writeText(content)
        }

    /** A folder "Urlaub" with pictures, a note, a sub folder, a hidden cache and a file in the trash. */
    private fun holidayFolder(withTrash: Boolean = true): File {
        val folder = tmp.newFolder("Urlaub")
        file(folder, "a.jpg")
        file(folder, "notes.txt")
        file(folder, "Tag 2/b.jpg")
        file(folder, "Tag 2/Abend/c.mp4")
        file(folder, ".thumbnails/a.jpg")
        if (withTrash) file(folder, ".trashed-1700000000-old.jpg")
        return folder
    }

    private fun scan(path: String) = FolderTransfer.scan(context, path)

    // ---------------------------------------------------------------- reading

    @Test
    fun scan_findsFilesAndSubFolders_withoutTrashAndHiddenFolders() {
        val folder = holidayFolder()

        val tree = scan(folder.path)

        assertEquals(listOf("Tag 2", "Tag 2/Abend"), tree.subFolders)
        assertEquals(4, tree.fileCount)
        assertEquals(
            setOf("a.jpg", "notes.txt", "b.jpg", "c.mp4"),
            tree.allFiles.map { File(it).name }.toSet()
        )
        assertTrue(tree.hasLeftOutFiles)
    }

    @Test
    fun localFolders_storageVolumesThemselvesAreNeverMoved() {
        val phone = "/storage/emulated/0"
        assertTrue(FolderTransfer.isMovableLocalFolder("/storage/emulated/0/DCIM/Camera", phone))
        assertTrue(FolderTransfer.isMovableLocalFolder("/storage/1234-ABCD/Bilder/", phone))
        assertFalse(FolderTransfer.isMovableLocalFolder("/storage/emulated/0", phone))
        assertFalse(FolderTransfer.isMovableLocalFolder("/storage/emulated/0/", phone))
        assertFalse(FolderTransfer.isMovableLocalFolder("/storage/1234-ABCD", phone))
        assertFalse(FolderTransfer.isMovableLocalFolder("/storage/emulated", phone))
        assertFalse(FolderTransfer.isMovableLocalFolder("/data/data/org.iurl.litegallery", phone))
    }

    @Test
    fun freeName_countsUpLikeFiles() {
        assertEquals("Urlaub", FolderTransfer.freeName("Urlaub", setOf("andere")))
        assertEquals("Urlaub (1)", FolderTransfer.freeName("Urlaub", setOf("urlaub")))
        assertEquals("Urlaub (2)", FolderTransfer.freeName("Urlaub", setOf("urlaub", "urlaub (1)")))
    }

    @Test
    fun shareItself_hasNoFolderOperations() {
        assertFalse(FolderTransfer.canHandle("fake://share"))
        assertTrue(FolderTransfer.canHandle("fake://share/Urlaub"))
    }

    // ---------------------------------------------------------------- copy / move on the phone

    @Test
    fun copy_takesEverythingAlong_andKeepsTheOriginal() {
        val folder = holidayFolder()
        val dest = tmp.newFolder("Ziel")

        val result = FolderTransfer.transfer(
            context, scan(folder.path), dest.path, "Urlaub", false, FileTransfer.Mode.COPY
        ) { _, _ -> }

        assertEquals(4, result.done)
        assertEquals(0, result.failed)
        assertEquals("a.jpg", File(dest, "Urlaub/a.jpg").readText())
        assertTrue(File(dest, "Urlaub/notes.txt").isFile)
        assertTrue(File(dest, "Urlaub/Tag 2/b.jpg").isFile)
        assertTrue(File(dest, "Urlaub/Tag 2/Abend/c.mp4").isFile)
        // Caches and the trash stay behind.
        assertFalse(File(dest, "Urlaub/.thumbnails").exists())
        assertFalse(File(dest, "Urlaub/.trashed-1700000000-old.jpg").exists())
        // The original is untouched.
        assertTrue(File(folder, "Tag 2/Abend/c.mp4").isFile)
    }

    @Test
    fun copy_withMerge_numbersTakenNames() {
        val folder = holidayFolder(withTrash = false)
        val dest = tmp.newFolder("Ziel")
        file(dest, "Urlaub/a.jpg", "older picture")

        val result = FolderTransfer.transfer(
            context, scan(folder.path), dest.path, "Urlaub", true, FileTransfer.Mode.COPY
        ) { _, _ -> }

        assertEquals(0, result.failed)
        assertEquals("older picture", File(dest, "Urlaub/a.jpg").readText())
        assertEquals("a.jpg", File(dest, "Urlaub/a (1).jpg").readText())
    }

    @Test
    fun move_onThePhone_renamesTheWholeFolder() {
        val folder = holidayFolder(withTrash = false)
        val dest = tmp.newFolder("Ziel")
        val oldPicture = File(folder, "Tag 2/b.jpg").path

        val result = FolderTransfer.transfer(
            context, scan(folder.path), dest.path, "Urlaub", false, FileTransfer.Mode.MOVE
        ) { _, _ -> }

        assertEquals(0, result.failed)
        assertFalse(folder.exists())
        assertTrue(File(dest, "Urlaub/Tag 2/b.jpg").isFile)
        // Everything went along in one step, the hidden cache too.
        assertTrue(File(dest, "Urlaub/.thumbnails/a.jpg").isFile)
        assertTrue(oldPicture in result.removedPaths)
        assertTrue(File(dest, "Urlaub/Tag 2/b.jpg").path in result.createdPaths)
    }

    @Test
    fun move_withPicturesInTheTrash_movesFileByFile_andTheTrashStays() {
        val folder = holidayFolder(withTrash = true)
        val dest = tmp.newFolder("Ziel")

        val result = FolderTransfer.transfer(
            context, scan(folder.path), dest.path, "Urlaub", false, FileTransfer.Mode.MOVE
        ) { _, _ -> }

        assertEquals(4, result.done)
        assertEquals(0, result.failed)
        assertTrue(File(dest, "Urlaub/Tag 2/Abend/c.mp4").isFile)
        assertFalse(File(folder, "a.jpg").exists())
        assertFalse(File(folder, "Tag 2").exists())
        // The picture in the trash can still be restored from where it is.
        assertTrue(File(folder, ".trashed-1700000000-old.jpg").isFile)
    }

    @Test
    fun cancelled_stopsBeforeTheFiles() {
        val folder = holidayFolder(withTrash = false)
        val dest = tmp.newFolder("Ziel")
        OperationCancel.request()

        val result = FolderTransfer.transfer(
            context, scan(folder.path), dest.path, "Urlaub", false, FileTransfer.Mode.COPY
        ) { _, _ -> }

        assertTrue(result.cancelled)
        assertEquals(0, result.done)
        assertTrue(File(folder, "a.jpg").isFile)
    }

    // ---------------------------------------------------------------- delete on the phone

    @Test
    fun delete_forGood_removesTheFolder() {
        val folder = holidayFolder(withTrash = false)

        val result = FolderTransfer.delete(context, scan(folder.path), false) { _, _ -> }

        assertEquals(4, result.done)
        assertEquals(0, result.failed)
        assertFalse(File(folder, "a.jpg").exists())
        assertFalse(File(folder, "Tag 2").exists())
    }

    @Test
    fun delete_toTrash_onThePhone_trashesPicturesAndVideosOnly() {
        val folder = holidayFolder(withTrash = false)

        val result = FolderTransfer.delete(context, scan(folder.path), true) { _, _ -> }

        assertEquals(3, result.done)
        assertEquals(0, result.failed)
        assertFalse(File(folder, "a.jpg").exists())
        assertTrue(File(folder, "${TrashBinStore.TRASH_FILE_PREFIX}a.jpg").isFile)
        assertTrue(File(folder, "Tag 2/${TrashBinStore.TRASH_FILE_PREFIX}b.jpg").isFile)
        // Not a picture: stays where it is.
        assertTrue(File(folder, "notes.txt").isFile)
    }

    @Test
    fun removeEmptiedLocal_reportsFilesThatAreStillThere() {
        val folder = tmp.newFolder("Rest")
        file(folder, "leer/.trashed-1-x.jpg")
        assertTrue(FolderTransfer.removeEmptiedLocal(folder))
        assertTrue(File(folder, "leer/.trashed-1-x.jpg").isFile)

        file(folder, "noch-da.jpg")
        assertFalse(FolderTransfer.removeEmptiedLocal(folder))
    }

    // ---------------------------------------------------------------- network share (fake)

    private fun shareFolder() {
        fake.files["fake://share/Urlaub/a.jpg"] = byteArrayOf(1, 2, 3)
        fake.files["fake://share/Urlaub/Tag 2/b.jpg"] = byteArrayOf(4, 5)
        fake.folders.add("fake://share/Urlaub/leer")
    }

    @Test
    fun share_copyToThePhone_keepsTheStructure() {
        shareFolder()
        val dest = tmp.newFolder("Handy")

        val tree = scan("fake://share/Urlaub")
        val result = FolderTransfer.transfer(
            context, tree, dest.path, "Urlaub", false, FileTransfer.Mode.COPY
        ) { _, _ -> }

        assertEquals(listOf("leer", "Tag 2"), tree.subFolders)
        assertEquals(2, result.done)
        assertArrayEquals(byteArrayOf(4, 5), File(dest, "Urlaub/Tag 2/b.jpg").readBytes())
        assertTrue(File(dest, "Urlaub/leer").isDirectory)
        assertTrue("fake://share/Urlaub/a.jpg" in fake.files)
    }

    @Test
    fun share_moveOnTheSameShare_inOneStep() {
        shareFolder()

        val result = FolderTransfer.transfer(
            context, scan("fake://share/Urlaub"), "fake://share/Archiv", "Urlaub", false,
            FileTransfer.Mode.MOVE
        ) { _, _ -> }

        assertEquals(0, result.failed)
        assertEquals(
            setOf("fake://share/Archiv/Urlaub/a.jpg", "fake://share/Archiv/Urlaub/Tag 2/b.jpg"),
            fake.files.keys
        )
        assertTrue("fake://share/Archiv/Urlaub/Tag 2/b.jpg" in result.createdPaths)
    }

    @Test
    fun share_moveToThePhone_removesTheFolderOnTheShare() {
        shareFolder()
        val dest = tmp.newFolder("Handy")

        val result = FolderTransfer.transfer(
            context, scan("fake://share/Urlaub"), dest.path, "Urlaub", false, FileTransfer.Mode.MOVE
        ) { _, _ -> }

        assertEquals(2, result.done)
        assertEquals(0, result.failed)
        assertTrue(fake.files.isEmpty())
        assertTrue(fake.folders.none { it.startsWith("fake://share/Urlaub") })
        assertTrue(File(dest, "Urlaub/a.jpg").isFile)
    }

    @Test
    fun share_delete_removesFilesAndFolder() {
        shareFolder()

        val result = FolderTransfer.delete(context, scan("fake://share/Urlaub"), true) { _, _ -> }

        assertEquals(2, result.done)
        assertEquals(0, result.failed)
        assertTrue(fake.files.isEmpty())
    }

    @Test
    fun share_deleteFails_folderStays_andIsReported() {
        shareFolder()
        fake.failDelete = true

        val result = FolderTransfer.delete(context, scan("fake://share/Urlaub"), false) { _, _ -> }

        assertEquals(2, result.failed)
        assertEquals(2, fake.files.size)
        assertTrue(fake.folders.contains("fake://share/Urlaub/leer"))
    }
}
