package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbTrashNameTest {

    @Test
    fun parseTrashedName_splitsTimeAndOriginalName() {
        val parsed = SmbTrash.parseTrashedName("1761570000000-0042__IMG_0001.jpg")

        requireNotNull(parsed)
        assertEquals(1761570000000L, parsed.first)
        assertEquals("IMG_0001.jpg", parsed.second)
    }

    @Test
    fun parseTrashedName_keepsSeparatorsInsideTheOriginalName() {
        val parsed = SmbTrash.parseTrashedName("1761570000000-0001__a__b-c (1).png")

        requireNotNull(parsed)
        assertEquals("a__b-c (1).png", parsed.second)
    }

    @Test
    fun parseTrashedName_rejectsOtherNames() {
        assertNull(SmbTrash.parseTrashedName("IMG_0001.jpg"))
        assertNull(SmbTrash.parseTrashedName("123-0001__short-time.jpg"))
        assertNull(SmbTrash.parseTrashedName("1761570000000-12__too-few-digits.jpg"))
        assertNull(SmbTrash.parseTrashedName(""))
    }
}
