package org.iurl.litegallery

import android.content.Context
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** A file or folder entry on a remote share, addressed by its full URL. */
data class RemoteFileInfo(
    val name: String,
    val url: String,            // full URL, e.g. smb://host/share/dir/a.jpg or nfs://host/export/dir/a.jpg
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,     // epoch millis
    val isMedia: Boolean,
    val isVideo: Boolean
)

/** Random-access read handle for a remote file (used by the Media3 DataSource). */
interface RemoteRandomAccessFile : Closeable {

    /** Total file size in bytes. */
    val size: Long

    /**
     * Read up to [length] bytes at absolute [fileOffset] into [buffer] starting at
     * [bufferOffset]. Returns the number of bytes read, or -1 at end of file.
     */
    @Throws(IOException::class)
    fun readAt(fileOffset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int
}

/**
 * Protocol-agnostic client for a remote file share. Implementations: [SmbFileClient]
 * (wraps SmbClient/smbj) and NfsFileClient (wraps the EMC NFSv3 client). All methods
 * address files by their full remote URL and perform blocking I/O — call them from
 * Dispatchers.IO.
 */
interface RemoteFileClient {

    fun listFiles(context: Context, folderUrl: String): List<RemoteFileInfo>

    @Throws(IOException::class)
    fun openInputStream(context: Context, fileUrl: String): InputStream

    @Throws(IOException::class)
    fun openRandomAccess(context: Context, fileUrl: String): RemoteRandomAccessFile

    /** File size in bytes, or -1 if it cannot be determined. */
    fun getFileSize(context: Context, fileUrl: String): Long

    fun rename(context: Context, oldUrl: String, newUrl: String): Boolean

    fun fileExists(context: Context, fileUrl: String): Boolean

    fun testConnection(config: NetworkServerConfig): Boolean

    fun disconnect(host: String)

    fun disconnectAll()
}

/** Routes a remote URL or a protocol name to the client that owns it. */
object RemoteClients {

    fun forUrl(url: String): RemoteFileClient? = when {
        SmbPath.isSmb(url) -> SmbFileClient
        NfsPath.isNfs(url) -> NfsFileClient
        else -> null
    }

    fun forProtocol(protocol: String): RemoteFileClient? = when (protocol) {
        NetworkServerConfig.PROTOCOL_SMB -> SmbFileClient
        NetworkServerConfig.PROTOCOL_NFS -> NfsFileClient
        else -> null
    }

    fun all(): List<RemoteFileClient> = listOf(SmbFileClient, NfsFileClient)
}

/** Path helpers shared by every remote protocol. */
object RemotePath {

    /** True if [path] belongs to any remote share protocol. */
    fun isRemote(path: String): Boolean =
        path.startsWith("smb://") || path.startsWith("nfs://")
}
