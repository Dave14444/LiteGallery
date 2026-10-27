package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class QuickBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, modified: Long): File =
        File(tmp.root, name).also {
            it.writeBytes(byteArrayOf(1))
            it.setLastModified(modified)
        }

    @Test
    fun filesToMove_leavesTheNewestFileOnThePhone() {
        file("old.jpg", 1_000_000L)
        file("middle.jpg", 2_000_000L)
        file("new.jpg", 3_000_000L)

        val names = QuickBackup.filesToMove(tmp.root.absolutePath)!!.map { it.name }

        assertEquals(listOf("old.jpg", "middle.jpg"), names)
    }

    @Test
    fun filesToMove_newestMayBeAVideo() {
        file("a.jpg", 1_000_000L)
        file("b.mp4", 2_000_000L)

        val names = QuickBackup.filesToMove(tmp.root.absolutePath)!!.map { it.name }

        assertEquals(listOf("a.jpg"), names)
    }

    @Test
    fun filesToMove_singleFileOrEmptyFolderMovesNothing() {
        assertTrue(QuickBackup.filesToMove(tmp.root.absolutePath)!!.isEmpty())

        file("only.jpg", 1_000_000L)

        assertTrue(QuickBackup.filesToMove(tmp.root.absolutePath)!!.isEmpty())
    }

    @Test
    fun filesToMove_ignoresOtherFilesHiddenFilesAndFolders() {
        file("a.jpg", 1_000_000L)
        file("b.jpg", 2_000_000L)
        file("notes.txt", 500_000L)
        file("document.pdf", 400_000L)
        file(".pending.jpg", 300_000L)
        file(".trashed-c.jpg", 200_000L)
        File(tmp.root, "subfolder.jpg").mkdir()

        val names = QuickBackup.filesToMove(tmp.root.absolutePath)!!.map { it.name }

        assertEquals(listOf("a.jpg"), names)
    }

    @Test
    fun filesToMove_sameTimeIsOrderedByName() {
        file("b.jpg", 1_000_000L)
        file("a.jpg", 1_000_000L)
        file("c.jpg", 1_000_000L)

        val names = QuickBackup.filesToMove(tmp.root.absolutePath)!!.map { it.name }

        assertEquals(listOf("a.jpg", "b.jpg"), names)
    }

    @Test
    fun filesToMove_unreadableFolderIsNull() {
        assertNull(QuickBackup.filesToMove(File(tmp.root, "does-not-exist").absolutePath))
    }

    @Test
    fun isMedia_recognisesPicturesAndVideosOnly() {
        assertTrue(QuickBackup.isMedia(File("x/IMG_1.JPG")))
        assertTrue(QuickBackup.isMedia(File("x/clip.MOV")))
        assertTrue(QuickBackup.isMedia(File("x/photo.heic")))
        assertFalse(QuickBackup.isMedia(File("x/notes.txt")))
        assertFalse(QuickBackup.isMedia(File("x/backup.zip")))
        assertFalse(QuickBackup.isMedia(File("x/noextension")))
        assertFalse(QuickBackup.isMedia(File("x/.hidden.jpg")))
    }
}
