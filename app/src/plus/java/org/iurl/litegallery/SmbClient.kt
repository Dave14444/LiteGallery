package org.iurl.litegallery

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig as SmbjConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SMB client wrapper around smbj library.
 * Manages connections, sessions, and file operations.
 */
object SmbClient {

    private val client: SMBClient by lazy {
        val config = SmbjConfig.builder()
            .withTimeout(60, TimeUnit.SECONDS)
            .withSoTimeout(0, TimeUnit.SECONDS)
            .build()
        SMBClient(config)
    }

    // Cache: host -> Connection
    private val connections = ConcurrentHashMap<String, Connection>()
    // Cache: "host|user" -> Session
    private val sessions = ConcurrentHashMap<String, Session>()
    // Cache: "host|user|share" -> DiskShare
    private val shares = ConcurrentHashMap<String, DiskShare>()

    private val imageExtensions = RemoteFileTypes.IMAGE_EXTENSIONS
    private val videoExtensions = RemoteFileTypes.VIDEO_EXTENSIONS

    data class SmbFileInfo(
        val name: String,
        val path: String,           // relative path within share
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,     // epoch millis
        val isMedia: Boolean,
        val isVideo: Boolean
    )

    /**
     * List available shares on an SMB server.
     */
    fun listShares(context: Context, host: String): List<String> {
        val config = resolveConfig(context, host) ?: throw SmbException("No config for host: $host")
        val session = getOrCreateSession(config)
        // Use a special share to list shares
        return try {
            // smbj doesn't have a direct listShares API on Session,
            // we connect to IPC$ to enumerate shares
            val transport = session.connection
            // Fallback: try common share names
            // For simplicity, we'll try to connect to well-known shares
            val shareNames = mutableListOf<String>()
            // Try listing via RPC
            try {
                val pipe = session.connectShare("IPC\$") as? com.hierynomus.smbj.share.PipeShare
                // Use the pipe to enumerate shares if available
                pipe?.close()
            } catch (e: Exception) {
                android.util.Log.d("SmbClient", "IPC$ enumeration not available: ${e.message}")
            }
            shareNames
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to list shares on $host", e)
            emptyList()
        }
    }

    /**
     * List files and directories in an SMB path.
     */
    fun listFiles(context: Context, host: String, shareName: String, path: String = ""): List<SmbFileInfo> {
        val dirPath = if (path.isBlank()) "" else path.replace('/', '\\').trimEnd('\\')

        try {
            return executeWithRetry(context, host, shareName) { share ->
                val results = mutableListOf<SmbFileInfo>()
                val listing = share.list(dirPath)
                for (info in listing) {
                    val name = info.fileName
                    // Skip . and .. entries
                    if (name == "." || name == ".." || name.startsWith(".")) continue

                    val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    val isHidden = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L
                    if (isHidden) continue

                    val ext = name.substringAfterLast('.', "").lowercase()
                    val isVideoFile = videoExtensions.contains(ext)
                    val isImageFile = imageExtensions.contains(ext)
                    val isMedia = isVideoFile || isImageFile

                    val relativePath = if (dirPath.isBlank()) name else "$dirPath\\$name"

                    results.add(
                        SmbFileInfo(
                            name = name,
                            path = relativePath.replace('\\', '/'),
                            isDirectory = isDir,
                            size = info.endOfFile,
                            lastModified = info.lastWriteTime.toEpochMillis(),
                            isMedia = isMedia,
                            isVideo = isVideoFile
                        )
                    )
                }
                results
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to list files: $dirPath on $shareName", e)
            throw SmbException("Failed to list files: ${e.message}", e)
        }
    }

    /**
     * Open an InputStream for reading an SMB file.
     * Caller is responsible for closing the returned InputStream.
     */
    fun openInputStream(context: Context, host: String, shareName: String, filePath: String): InputStream {
        val smbPath = filePath.replace('/', '\\')

        try {
            return executeWithRetry(context, host, shareName) { share ->
                val file = share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                // Closing smbj's stream does not close the file handle. A handle that stays open
                // keeps the file "delete pending" on the server (deleted files stay visible), so the
                // returned stream closes the handle as well.
                val stream = file.inputStream
                object : java.io.FilterInputStream(stream) {
                    private var closed = false

                    override fun close() {
                        if (closed) return
                        closed = true
                        try {
                            super.close()
                        } catch (_: Exception) {
                        }
                        try {
                            file.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to open file: $smbPath", e)
            throw SmbException("Failed to open file: ${e.message}", e)
        }
    }


    
    
    /**
     * Write bytes to a file on the SMB share, creating the parent
     * directory when necessary.
     */
    fun writeBytes(
        context: Context,
        host: String,
        shareName: String,
        filePath: String,
        bytes: ByteArray
    ) {
        val smbPath = filePath.replace('/', '\\')
        val parent = smbPath.substringBeforeLast('\\', "")

        try {
            executeWithRetry(context, host, shareName) { share ->
                if (parent.isNotBlank() && !share.folderExists(parent)) {
                    try {
                        share.mkdir(parent)
                    } catch (e: Exception) {
                        if (!share.folderExists(parent)) {
                            throw e
                        }
                    }
                }

                val file = share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    EnumSet.of(
                        SMB2ShareAccess.FILE_SHARE_READ,
                        SMB2ShareAccess.FILE_SHARE_WRITE,
                        SMB2ShareAccess.FILE_SHARE_DELETE
                    ),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null
                )

                try {
                    file.outputStream.use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                } finally {
                    file.close()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to write: $smbPath", e)
            throw e
        }
    }

    /**
     * Open an SMB file for reading with seek support (for ExoPlayer DataSource).
     * Returns the smbj File object. Caller must close it.
     */
    fun openFile(
        context: Context,
        host: String,
        shareName: String,
        filePath: String
    ): com.hierynomus.smbj.share.File {
        val smbPath = filePath.replace('/', '\\')

        try {
            return executeWithRetry(context, host, shareName) { share ->
                share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to open file: $smbPath", e)
            throw SmbException("Failed to open file: ${e.message}", e)
        }
    }

    /**
     * Get file size without opening the full file.
     */
    fun getFileSize(context: Context, host: String, shareName: String, filePath: String): Long {
        val smbPath = filePath.replace('/', '\\')
        return try {
            executeWithRetry(context, host, shareName) { share ->
                val info = share.getFileInformation(smbPath)
                info.standardInformation.endOfFile
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to get file size: $smbPath", e)
            -1L
        }
    }


    /**
     * Get the last-modified timestamp of a file on the SMB share.
     */
    fun getLastModified(
        context: Context,
        host: String,
        shareName: String,
        filePath: String
    ): Long {
        val smbPath = filePath.replace('/', '\\')
        return try {
            executeWithRetry(context, host, shareName) { share ->
                share.getFileInformation(smbPath).basicInformation.lastWriteTime.toEpochMillis()
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to get last-modified time: $smbPath", e)
            -1L
        }
    }

        /**
     * Get file size and last-modified timestamp with a single query.
     * Returns (-1, -1) on failure, like the two separate functions do.
     */
    fun getFileSizeAndLastModified(
        context: Context,
        host: String,
        shareName: String,
        filePath: String
    ): Pair<Long, Long> {
        val smbPath = filePath.replace('/', '\\')
        return try {
            executeWithRetry(context, host, shareName) { share ->
                val info = share.getFileInformation(smbPath)
                Pair(
                    info.standardInformation.endOfFile,
                    info.basicInformation.lastWriteTime.toEpochMillis()
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to get file info: $smbPath", e)
            Pair(-1L, -1L)
        }
    }
    
    /**
     * Rename a file on the SMB share.
     */
    fun rename(
        context: Context,
        host: String,
        shareName: String,
        oldPath: String,
        newPath: String
    ): Boolean {
        val oldSmbPath = oldPath.replace('/', '\\')
        val newSmbPath = newPath.replace('/', '\\')

        return try {
            executeWithRetry(context, host, shareName) { share ->
                val file = share.openFile(
                    oldSmbPath,
                    EnumSet.of(AccessMask.GENERIC_ALL, AccessMask.DELETE),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                try {
                    file.rename(newSmbPath)
                } finally {
                    file.close()
                }
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to rename: $oldSmbPath -> $newSmbPath", e)
            false
        }
    }

    /**
     * Delete a file on the SMB share. Returns true once the file is really gone.
     */
    fun deleteFile(context: Context, host: String, shareName: String, filePath: String): Boolean {
        val smbPath = filePath.replace('/', '\\')
        try {
            val stillThere = executeWithRetry(context, host, shareName) { share ->
                share.rm(smbPath)
                share.fileExists(smbPath)
            }
            if (!stillThere) return true

            // The server keeps a file that is still open somewhere "delete pending". Dropping our
            // connection closes every handle we may still hold; then check again.
            disconnect(host)
            return !fileExists(context, host, shareName, filePath)
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to delete: $smbPath", e)
            return false
        }
    }

    /** Create a folder (and nothing else) on the share. An already existing folder counts as success. */
    fun createDirectory(context: Context, host: String, shareName: String, folderPath: String): Boolean {
        val smbPath = folderPath.replace('/', '\\')
        return try {
            executeWithRetry(context, host, shareName) { share ->
                if (!share.folderExists(smbPath)) {
                    share.mkdir(smbPath)
                }
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to create folder: $smbPath", e)
            false
        }
    }

    /**
     * Stream [input] into a NEW file on the share (fails if it already exists). A partial file is
     * removed on failure. When [lastModified] is given, the file keeps that modification time (so
     * copied photos stay in their original order). The caller closes [input].
     */
    fun writeStream(
        context: Context,
        host: String,
        shareName: String,
        filePath: String,
        input: InputStream,
        lastModified: Long = 0L
    ): Boolean {
        val smbPath = filePath.replace('/', '\\')
        var created = false
        return try {
            executeWithRetry(context, host, shareName) { share ->
                val file = share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_CREATE,
                    null
                )
                created = true
                try {
                    file.outputStream.use { output ->
                        input.copyTo(output, 256 * 1024)
                        output.flush()
                    }
                    if (lastModified > 0L) {
                        applyLastModified(file, lastModified)
                    }
                } finally {
                    file.close()
                }
                if (lastModified > 0L) {
                    keepModificationTime(share, smbPath, lastModified)
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to write: $smbPath", e)
            if (created) {
                deleteFile(context, host, shareName, filePath)
            }
            false
        }
    }

    /**
     * Sets the modification time of an open file. Failing to do so never fails the copy.
     * Nothing is read first (the handle may not be allowed to read attributes); attributes = 0
     * means "unchanged".
     */
    private fun applyLastModified(file: com.hierynomus.smbj.share.File, epochMillis: Long) {
        try {
            val time = FileTime.ofEpochMillis(epochMillis)
            file.setFileInformation(FileBasicInformation(time, time, time, time, 0L))
        } catch (e: Exception) {
            android.util.Log.w("SmbClient", "Could not keep the modification time", e)
        }
    }

    /**
     * Some servers update the modification time again when a written file is closed: check it
     * after closing and set it once more through a fresh handle if it differs.
     */
    private fun keepModificationTime(
        share: com.hierynomus.smbj.share.DiskShare,
        smbPath: String,
        epochMillis: Long
    ) {
        try {
            val actual = share.getFileInformation(smbPath).basicInformation.lastWriteTime.toEpochMillis()
            if (kotlin.math.abs(actual - epochMillis) <= 2000L) return

            val file = share.openFile(
                smbPath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                EnumSet.of(
                    SMB2ShareAccess.FILE_SHARE_READ,
                    SMB2ShareAccess.FILE_SHARE_WRITE,
                    SMB2ShareAccess.FILE_SHARE_DELETE
                ),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            try {
                applyLastModified(file, epochMillis)
            } finally {
                file.close()
            }
        } catch (e: Exception) {
            android.util.Log.w("SmbClient", "Could not check the modification time", e)
        }
    }

    /**
     * Check if a file exists on the SMB share.
     */
    fun fileExists(context: Context, host: String, shareName: String, filePath: String): Boolean {
        val smbPath = filePath.replace('/', '\\')
        return try {
            executeWithRetry(context, host, shareName) { share ->
                share.fileExists(smbPath)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Test connection to an SMB server.
     * Returns true if connection is successful.
     */
    fun testConnection(config: NetworkServerConfig): Boolean {
        return try {
            val connection = client.connect(config.host, config.port)
            val authContext = if (config.isGuest) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(
                    config.username,
                    config.password.toCharArray(),
                    ""
                )
            }
            val session = connection.authenticate(authContext)
            session.close()
            connection.close()
            true
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Connection test failed for ${config.host}", e)
            false
        }
    }

    /**
     * Disconnect all connections and clear caches.
     */
    fun disconnectAll() {
        val sharesToClose = shares.values.toList()
        shares.clear()

        val sessionsToClose = sessions.values.toList()
        sessions.clear()

        val connsToClose = connections.values.toList()
        connections.clear()

        Thread {
            sharesToClose.forEach { try { it.close() } catch (_: Exception) {} }
            sessionsToClose.forEach { try { it.close() } catch (_: Exception) {} }
            connsToClose.forEach { try { it.close() } catch (_: Exception) {} }
        }.start()
    }

    /**
     * Disconnect a specific host.
     */
    fun disconnect(host: String) {
        val keysToRemove = shares.keys.filter { it.startsWith("$host|") }
        val sharesToClose = keysToRemove.mapNotNull { shares.remove(it) }

        val sessionKeysToRemove = sessions.keys.filter { it.startsWith("$host|") }
        val sessionsToClose = sessionKeysToRemove.mapNotNull { sessions.remove(it) }

        val connToClose = connections.remove(host)

        Thread {
            sharesToClose.forEach { try { it.close() } catch (_: Exception) {} }
            sessionsToClose.forEach { try { it.close() } catch (_: Exception) {} }
            connToClose?.let { try { it.close() } catch (_: Exception) {} }
        }.start()
    }

    // --- Internal helpers ---

    private fun resolveConfig(context: Context, host: String): NetworkServerConfig? {
        return NetworkConfigStore.getServerByHost(context, host, NetworkServerConfig.PROTOCOL_SMB)
    }

    @Synchronized
    private fun getOrCreateConnection(config: NetworkServerConfig): Connection {
        val existing = connections[config.host]
        if (existing != null && existing.isConnected) {
            return existing
        }

        // Remove stale connection
        existing?.let {
            connections.remove(config.host)
            Thread { try { it.close() } catch (_: Exception) {} }.start()
        }

        val connection = client.connect(config.host, config.port)
        connections[config.host] = connection
        return connection
    }

    @Synchronized
    private fun getOrCreateSession(config: NetworkServerConfig): Session {
        val key = "${config.host}|${config.username}"
        val existing = sessions[key]
        if (existing != null) {
            try {
                // Check if session is still valid
                if (existing.connection.isConnected) {
                    return existing
                }
            } catch (_: Exception) {
            }
            sessions.remove(key)
            Thread { try { existing.close() } catch (_: Exception) {} }.start()
        }

        val connection = getOrCreateConnection(config)
        val authContext = if (config.isGuest) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(
                config.username,
                config.password.toCharArray(),
                ""
            )
        }
        val session = connection.authenticate(authContext)
        sessions[key] = session
        return session
    }

    @Synchronized
    private fun getOrCreateShare(context: Context, host: String, shareName: String): DiskShare {
        val config = resolveConfig(context, host) ?: throw SmbException("No config for host: $host")
        return getOrCreateShareWithConfig(config, shareName)
    }

    @Synchronized
    private fun getOrCreateShareWithConfig(config: NetworkServerConfig, shareName: String): DiskShare {
        val key = "${config.host}|${config.username}|$shareName"
        val existing = shares[key]
        if (existing != null) {
            try {
                // Check if share is still valid
                if (existing.isConnected) {
                    return existing
                }
            } catch (_: Exception) {
            }
            shares.remove(key)
            Thread { try { existing.close() } catch (_: Exception) {} }.start()
        }

        val session = getOrCreateSession(config)
        val share = session.connectShare(shareName) as DiskShare
        shares[key] = share
        return share
    }

    private fun <T> executeWithRetry(context: Context, host: String, shareName: String, block: (DiskShare) -> T): T {
        try {
            val share = getOrCreateShare(context, host, shareName)
            return block(share)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val causeMsg = e.cause?.message ?: ""
            if (msg.contains("closed", ignoreCase = true) || causeMsg.contains("closed", ignoreCase = true) ||
                msg.contains("Connection reset", ignoreCase = true) || causeMsg.contains("Connection reset", ignoreCase = true) ||
                msg.contains("Broken pipe", ignoreCase = true) || causeMsg.contains("Broken pipe", ignoreCase = true)) {
                
                android.util.Log.w("SmbClient", "SMB connection appears stale, retrying...", e)
                disconnect(host)
                val newShare = getOrCreateShare(context, host, shareName)
                return block(newShare)
            }
            throw e
        }
    }

    class SmbException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
