package org.iurl.litegallery

import android.content.Context
import java.io.IOException
import java.io.InputStream

/**
 * [RemoteFileClient] adapter for SMB. Parses smb:// URLs and delegates to [SmbClient],
 * which keeps all smbj connection handling unchanged.
 */
object SmbFileClient : RemoteFileClient {

    override fun listFiles(context: Context, folderUrl: String): List<RemoteFileInfo> {
        val smbPath = SmbPath.parse(folderUrl) ?: return emptyList()
        return SmbClient.listFiles(context, smbPath.host, smbPath.share, smbPath.path).map { info ->
            RemoteFileInfo(
                name = info.name,
                url = SmbPath.toUrl(smbPath.host, smbPath.share, info.path),
                isDirectory = info.isDirectory,
                size = info.size,
                lastModified = info.lastModified,
                isMedia = info.isMedia,
                isVideo = info.isVideo
            )
        }
    }

    override fun openInputStream(context: Context, fileUrl: String): InputStream {
        val smbPath = SmbPath.parse(fileUrl) ?: throw IOException("Invalid SMB URL: $fileUrl")
        return SmbClient.openInputStream(context, smbPath.host, smbPath.share, smbPath.path)
    }

    override fun openRandomAccess(context: Context, fileUrl: String): RemoteRandomAccessFile {
        val smbPath = SmbPath.parse(fileUrl) ?: throw IOException("Invalid SMB URL: $fileUrl")
        val file = SmbClient.openFile(context, smbPath.host, smbPath.share, smbPath.path)
        val fileSize = file.fileInformation.standardInformation.endOfFile
        return object : RemoteRandomAccessFile {
            override val size: Long = fileSize

            override fun readAt(
                fileOffset: Long,
                buffer: ByteArray,
                bufferOffset: Int,
                length: Int
            ): Int = file.read(buffer, fileOffset, bufferOffset, length)

            override fun close() = file.close()
        }
    }

    override fun getFileSize(context: Context, fileUrl: String): Long {
        val smbPath = SmbPath.parse(fileUrl) ?: return -1L
        return SmbClient.getFileSize(context, smbPath.host, smbPath.share, smbPath.path)
    }

    override fun rename(context: Context, oldUrl: String, newUrl: String): Boolean {
        val oldPath = SmbPath.parse(oldUrl) ?: return false
        val newPath = SmbPath.parse(newUrl) ?: return false
        if (!oldPath.host.equals(newPath.host, ignoreCase = true) || oldPath.share != newPath.share) {
            return false
        }
        return SmbClient.rename(context, oldPath.host, oldPath.share, oldPath.path, newPath.path)
    }

    override fun fileExists(context: Context, fileUrl: String): Boolean {
        val smbPath = SmbPath.parse(fileUrl) ?: return false
        return SmbClient.fileExists(context, smbPath.host, smbPath.share, smbPath.path)
    }

    override fun testConnection(config: NetworkServerConfig): Boolean =
        SmbClient.testConnection(config)

    override fun disconnect(host: String) = SmbClient.disconnect(host)

    override fun disconnectAll() = SmbClient.disconnectAll()
}
