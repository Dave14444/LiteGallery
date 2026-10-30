package org.iurl.litegallery

import com.hierynomus.msfscc.FileAttributes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbFolderLeftoversTest {

    @Test
    fun thumbnailsAndSettingsOfNasAndComputers_mayGoWithAFolder() {
        assertTrue(SmbClient.isDisposable(".wdmc", true))
        assertTrue(SmbClient.isDisposable("@eaDir", true))
        assertTrue(SmbClient.isDisposable("Thumbs.db", false))
        assertTrue(SmbClient.isDisposable(".DS_Store", false))
        assertTrue(SmbClient.isDisposable("._IMG_0001.JPG", false))
    }

    @Test
    fun picturesAndUnknownHiddenThings_keepTheFolder() {
        assertFalse(SmbClient.isDisposable("IMG_0001.JPG", false))
        assertFalse(SmbClient.isDisposable(".privat", true))
        assertFalse(SmbClient.isDisposable(".geheim.jpg", false))
        // Only a folder of that name is a thumbnail cache.
        assertFalse(SmbClient.isDisposable(".wdmc", false))
    }

    @Test
    fun hiddenEntries_areWhatTheListLeavesOut() {
        assertTrue(SmbClient.isHiddenEntry(".", 0L))
        assertTrue(SmbClient.isHiddenEntry(".wdmc", 0L))
        assertTrue(SmbClient.isHiddenEntry("Bild.jpg", FileAttributes.FILE_ATTRIBUTE_HIDDEN.value))
        assertFalse(SmbClient.isHiddenEntry("Bild.jpg", FileAttributes.FILE_ATTRIBUTE_ARCHIVE.value))
    }
}
