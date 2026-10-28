package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailCleanupRootsTest {

    private val share = "smb://192.168.1.10/David"

    @Test
    fun onlyTheConnectionFolderIsSearched() {
        assertEquals(
            listOf("smb://192.168.1.10/David/Bilder"),
            ThumbnailCleanup.searchRoots(share, listOf("smb://192.168.1.10/David/Bilder"))
        )
    }

    @Test
    fun withoutConnectionOnTheShareTheWholeShareIsSearched() {
        assertEquals(
            listOf(share),
            ThumbnailCleanup.searchRoots(share, listOf("smb://192.168.1.10/Other/Bilder"))
        )
        assertEquals(listOf(share), ThumbnailCleanup.searchRoots(share, emptyList()))
    }

    @Test
    fun connectionsInsideOthersAreCoveredByTheOuterOne() {
        assertEquals(
            listOf("smb://192.168.1.10/David/Bilder", "smb://192.168.1.10/David/Videos"),
            ThumbnailCleanup.searchRoots(
                share,
                listOf(
                    "smb://192.168.1.10/David/Bilder",
                    "smb://192.168.1.10/David/Bilder/2026/",
                    "smb://192.168.1.10/David/Videos"
                )
            )
        )
    }

    @Test
    fun aConnectionToTheWholeShareCoversEverything() {
        assertEquals(
            listOf(share),
            ThumbnailCleanup.searchRoots(share, listOf("$share/", "$share/Bilder"))
        )
    }

    @Test
    fun similarNamesAreNotMixedUp() {
        // "David2" is another share, not a folder inside "David".
        assertEquals(
            listOf(share),
            ThumbnailCleanup.searchRoots(share, listOf("smb://192.168.1.10/David2/Bilder"))
        )
    }

    @Test
    fun letterCaseAndDuplicatesDoNotMatter() {
        assertEquals(
            listOf("smb://192.168.1.10/David/Bilder"),
            ThumbnailCleanup.searchRoots(
                share,
                listOf("smb://192.168.1.10/David/Bilder", "SMB://192.168.1.10/david/bilder/")
            )
        )
    }
}
