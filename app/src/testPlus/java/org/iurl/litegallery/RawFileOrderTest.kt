package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Test

class RawFileOrderTest {

    private fun raw(name: String, modified: Long, size: Long = 1000L) =
        RemoteFileInfo(name, "smb://nas/share/raws/$name", false, size, modified, false, false)

    // Named in the order the camera took them; one copied later so its time is newest.
    private val files = listOf(
        raw("IMG_0001.CR2", modified = 1_000L, size = 30L),
        raw("IMG_0002.CR2", modified = 2_000L, size = 10L),
        raw("img_0003.cr2", modified = 3_000L, size = 20L),
        raw("IMG_0004.CR2", modified = 3_000L, size = 20L)
    )

    private fun order(sortOrder: String) = files.shuffled().sortedWith(rawFileOrder(sortOrder)).map { it.name }

    @Test
    fun newestFirst_isTheDefault() {
        val expected = listOf("IMG_0004.CR2", "img_0003.cr2", "IMG_0002.CR2", "IMG_0001.CR2")
        assertEquals(expected, order("date_desc"))
        assertEquals(expected, order("unknown"))
    }

    @Test
    fun oldestFirst() {
        assertEquals(listOf("IMG_0001.CR2", "IMG_0002.CR2", "img_0003.cr2", "IMG_0004.CR2"), order("date_asc"))
    }

    @Test
    fun byName_ignoresCase() {
        assertEquals(listOf("IMG_0001.CR2", "IMG_0002.CR2", "img_0003.cr2", "IMG_0004.CR2"), order("name_asc"))
        assertEquals(listOf("IMG_0004.CR2", "img_0003.cr2", "IMG_0002.CR2", "IMG_0001.CR2"), order("name_desc"))
    }

    @Test
    fun bySize() {
        assertEquals(listOf("IMG_0001.CR2", "IMG_0004.CR2", "img_0003.cr2", "IMG_0002.CR2"), order("size_desc"))
        assertEquals(listOf("IMG_0002.CR2", "img_0003.cr2", "IMG_0004.CR2", "IMG_0001.CR2"), order("size_asc"))
    }
}
