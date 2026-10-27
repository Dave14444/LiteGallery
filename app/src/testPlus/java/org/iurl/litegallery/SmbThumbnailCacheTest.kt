package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SmbThumbnailCacheTest {

    private val url = "smb://nas/Photos/2026/a.jpg"

    @Test
    fun cacheKey_isStableAndLooksLikeASha256() {
        val key = SmbThumbnailCache.cacheKey(url, 1234L, 99L)

        assertEquals(key, SmbThumbnailCache.cacheKey(url, 1234L, 99L))
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun cacheKey_changesWhenThePictureChanges() {
        val key = SmbThumbnailCache.cacheKey(url, 1234L, 99L)

        assertNotEquals(key, SmbThumbnailCache.cacheKey(url, 1235L, 99L))
        assertNotEquals(key, SmbThumbnailCache.cacheKey(url, 1234L, 100L))
        assertNotEquals(key, SmbThumbnailCache.cacheKey("smb://nas/Photos/2026/b.jpg", 1234L, 99L))
    }

    @Test
    fun unusedNames_listsOnlyPreviewsOfPicturesThatAreGone() {
        val inUse = SmbThumbnailCache.cacheKey(url, 1L, 1L)
        val videoInUse = SmbThumbnailCache.cacheKey("smb://nas/Photos/v.mp4", 2L, 2L)
        val gone = SmbThumbnailCache.cacheKey("smb://nas/Photos/deleted.jpg", 3L, 3L)
        val goneVideo = SmbThumbnailCache.cacheKey("smb://nas/Photos/deleted.mp4", 4L, 4L)

        val unused = SmbThumbnailCache.unusedNames(
            listOf("$inUse.jpg", "$videoInUse.jpg", "$videoInUse.dur", "$gone.jpg", "$goneVideo.jpg", "$goneVideo.dur"),
            setOf(inUse, videoInUse)
        )

        assertEquals(listOf("$gone.jpg", "$goneVideo.jpg", "$goneVideo.dur"), unused)
    }

    @Test
    fun unusedNames_neverTouchesOtherFiles() {
        val unused = SmbThumbnailCache.unusedNames(
            listOf("notes.txt", "README", "Thumbs.db", "something.png"),
            emptySet()
        )

        assertTrue(unused.isEmpty())
    }

    @Test
    fun unusedNames_withNoPicturesLeftEverythingIsUnused() {
        val a = SmbThumbnailCache.cacheKey(url, 1L, 1L)

        assertEquals(listOf("$a.jpg"), SmbThumbnailCache.unusedNames(listOf("$a.jpg"), emptySet()))
    }
}
