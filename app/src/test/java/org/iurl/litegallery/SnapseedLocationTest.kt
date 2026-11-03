package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapseedLocationTest {

    // Like a share: the folder of a path, none above the share itself.
    private val parentOf: (String) -> String? = { path ->
        val trimmed = path.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/')
        if (parent == "smb:/" || parent.count { it == '/' } < 3) null else parent
    }

    @Test
    fun rawFile_getsTheFolderNextToItsOwnFolder() {
        assertEquals(
            "smb://nas/Public/Urlaub",
            ExternalEditFlow.snapseedLocation("smb://nas/Public/Urlaub/RAW/IMG_0001.CR2", parentOf)
        )
    }

    @Test
    fun otherPictures_keepTheFolderNextToThem() {
        assertEquals(
            "smb://nas/Public/Urlaub",
            ExternalEditFlow.snapseedLocation("smb://nas/Public/Urlaub/IMG_0001.jpg", parentOf)
        )
    }

    @Test
    fun rawFileAtTheTopOfTheShare_staysInThatFolder() {
        assertEquals(
            "smb://nas/Public",
            ExternalEditFlow.snapseedLocation("smb://nas/Public/IMG_0001.nef", parentOf)
        )
    }
}
