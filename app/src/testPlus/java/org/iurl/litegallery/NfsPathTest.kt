package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfsPathTest {

    @Test
    fun parse_validFileUrlExposesDerivedProperties() {
        val path = NfsPath.parse("nfs://nas.local/volume1/photo/Trips/img.name.jpg")

        requireNotNull(path)
        assertEquals("nas.local", path.host)
        assertEquals("/volume1/photo/Trips/img.name.jpg", path.fullPath)
        assertEquals("nfs://nas.local/volume1/photo/Trips/img.name.jpg", path.fullUrl)
        assertEquals("img.name.jpg", path.fileName)
        assertEquals("img.name", path.fileNameWithoutExtension)
        assertEquals("jpg", path.fileExtension)
        assertEquals("nfs://nas.local/volume1/photo/Trips", path.parentFolderPath)
    }

    @Test
    fun parse_hostRootAndTrailingSlashes() {
        assertEquals(NfsPath("nas", ""), NfsPath.parse("nfs://nas"))
        assertEquals(NfsPath("nas", ""), NfsPath.parse("nfs://nas/"))
        assertEquals(NfsPath("nas", "/volume1"), NfsPath.parse("nfs://nas/volume1/"))
    }

    @Test
    fun parse_rejectsInvalidUrls() {
        assertNull(NfsPath.parse("smb://host/share/file.jpg"))
        assertNull(NfsPath.parse("nfs://"))
        assertNull(NfsPath.parse("nfs:///volume1"))
    }

    @Test
    fun isNfs_matchesOnlyNfsScheme() {
        assertTrue(NfsPath.isNfs("nfs://host/volume1"))
        assertFalse(NfsPath.isNfs("NFS://host/volume1"))
        assertFalse(NfsPath.isNfs("/storage/emulated/0/a.jpg"))
    }

    @Test
    fun innerPath_stripsExportPrefix() {
        val path = NfsPath("h", "/volume1/photo/a/b.jpg")
        assertEquals("/a/b.jpg", path.innerPath("/volume1/photo"))
        assertEquals("/", NfsPath("h", "/volume1/photo").innerPath("/volume1/photo"))
        assertNull(path.innerPath("/volume1/music"))
        // A path in a sibling folder must not match by raw string prefix.
        assertNull(NfsPath("h", "/volume1/photos2/x.jpg").innerPath("/volume1/photo"))
    }

    @Test
    fun matchExport_selectsLongestMatchingPrefix() {
        val exports = listOf("/volume1", "/volume1/photo")
        assertEquals("/volume1/photo", NfsPath.matchExport(exports, "/volume1/photo/x.jpg"))
        assertEquals("/volume1", NfsPath.matchExport(exports, "/volume1/music/y.mp4"))
        assertEquals("/volume1/photo", NfsPath.matchExport(exports, "/volume1/photo"))
        assertNull(NfsPath.matchExport(exports, "/volume2/z.jpg"))
        assertNull(NfsPath.matchExport(emptyList(), "/volume1/photo/x.jpg"))
    }

    @Test
    fun toUrl_joinsHostAndFullPath() {
        assertEquals("nfs://nas/volume1/photo", NfsPath.toUrl("nas", "/volume1/photo"))
        assertEquals("nfs://nas", NfsPath.toUrl("nas", ""))
    }
}
