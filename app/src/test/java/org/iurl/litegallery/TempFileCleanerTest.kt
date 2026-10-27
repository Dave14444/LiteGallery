package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class TempFileCleanerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hour = 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    private fun file(path: String, ageMs: Long): File {
        val file = File(tmp.root, path)
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1))
        file.setLastModified(now - ageMs)
        return file
    }

    @Test
    fun oldFilesInShareAndPhotoEditAreRemoved_newOnesStay() {
        val oldShare = file("share/shared_1.jpg", 2 * hour)
        val newShare = file("share/shared_2.jpg", hour / 2)
        val oldEdit = file("photo_edit/work.jpg", 3 * hour)

        val removed = TempFileCleaner.cleanUp(tmp.root, now)

        assertEquals(2, removed)
        assertFalse(oldShare.exists())
        assertFalse(oldEdit.exists())
        assertTrue(newShare.exists())
    }

    @Test
    fun externalEditIsKeptForADay() {
        val recent = file("external_edit/a.jpg", 5 * hour)
        val old = file("external_edit/b.jpg", 25 * hour)

        TempFileCleaner.cleanUp(tmp.root, now)

        assertTrue(recent.exists())
        assertFalse(old.exists())
    }

    @Test
    fun otherFilesAndFoldersInTheCacheAreNeverTouched() {
        val glide = file("image_manager_disk_cache/abc.0", 100 * hour)
        val loose = file("something.tmp", 100 * hour)

        TempFileCleaner.cleanUp(tmp.root, now)

        assertTrue(glide.exists())
        assertTrue(loose.exists())
    }

    @Test
    fun emptySubFoldersAreRemovedButNotTheKnownFolderItself() {
        file("share/nested/old.jpg", 5 * hour)

        TempFileCleaner.cleanUp(tmp.root, now)

        assertFalse(File(tmp.root, "share/nested").exists())
        assertTrue(File(tmp.root, "share").isDirectory)
    }

    @Test
    fun missingFoldersAreNoProblem() {
        assertEquals(0, TempFileCleaner.cleanUp(tmp.root, now))
    }
}
