package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteClientsTest {

    @Test
    fun forUrl_routesSmbUrlsToSmbFileClient() {
        assertSame(SmbFileClient, RemoteClients.forUrl("smb://host/share/file.jpg"))
    }

    @Test
    fun forUrl_routesNfsUrlsToNfsFileClient() {
        assertSame(NfsFileClient, RemoteClients.forUrl("nfs://host/volume1/photo/file.jpg"))
    }

    @Test
    fun forUrl_returnsNullForLocalAndUnknownSchemes() {
        assertNull(RemoteClients.forUrl("/storage/emulated/0/DCIM/a.jpg"))
        assertNull(RemoteClients.forUrl("content://media/external/images/media/1"))
    }

    @Test
    fun forProtocol_routesBothProtocols() {
        assertSame(SmbFileClient, RemoteClients.forProtocol(NetworkServerConfig.PROTOCOL_SMB))
        assertSame(NfsFileClient, RemoteClients.forProtocol(NetworkServerConfig.PROTOCOL_NFS))
        assertNull(RemoteClients.forProtocol("gopher"))
    }

    @Test
    fun isRemote_matchesSmbAndNfsSchemesOnly() {
        assertTrue(RemotePath.isRemote("smb://host/share"))
        assertTrue(RemotePath.isRemote("nfs://host/volume1/photo"))
        assertFalse(RemotePath.isRemote("/sdcard/x.jpg"))
        assertFalse(RemotePath.isRemote("https://example.com/a.jpg"))
    }

    @Test
    fun fileTypes_classifyByExtension() {
        assertTrue(RemoteFileTypes.isImage("photo.JPG"))
        assertTrue(RemoteFileTypes.isVideo("clip.mkv"))
        assertTrue(RemoteFileTypes.isMedia("photo.heic"))
        assertFalse(RemoteFileTypes.isMedia("document.pdf"))
        assertEquals("video/mp4", RemoteFileTypes.mimeType("clip.m4v"))
        assertEquals("image/jpeg", RemoteFileTypes.mimeType("photo.jpeg"))
    }
}
