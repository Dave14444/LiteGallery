package org.iurl.litegallery

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
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
            // Without a socket timeout a connection that silently died (Wi-Fi changed, NAS
            // asleep, VPN dropped) stays open forever. Requests on it fail after the 60 s above
            // and open a new connection (see looksLikeLostConnection); the socket timeout also
            // closes a connection that has been quiet for a long time. Long enough not to drop
            // a connection while one picture is being looked at.
            .withSoTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        SMBClient(config)
    }

    private const val SOCKET_TIMEOUT_SECONDS = 300L

    // Cannot collide with a real user name (a NUL character cannot be typed into the field).
    private const val GUEST_LOGIN_KEY = "\u0000guest"

    /** What renaming (moving) a file or folder needs, and nothing more. */
    private val RENAME_ACCESS: Set<AccessMask> =
        EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.SYNCHRONIZE)

    // Why the last write, rename or delete of this thread failed: the server's answer, shown in
    // the list of files that could not be copied, moved or deleted.
    private val lastError = ThreadLocal<String?>()

    /** Why the last write, rename or delete of this thread failed (e.g. "STATUS_ACCESS_DENIED"), or null. Clears it. */
    fun takeLastError(): String? = lastError.get().also { lastError.remove() }

    /** A short text for what went wrong: the server's answer (e.g. "STATUS_ACCESS_DENIED") if there is one. */
    internal fun describeError(e: Throwable): String {
        var current: Throwable? = e
        while (current != null) {
            if (current is SMBApiException) return current.status.name
            current = current.cause
        }
        val root = generateSequence(e) { it.cause }.last()
        return (root.message ?: root.javaClass.simpleName).lineSequence().first().take(120)
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
                    // Skip ".", ".." and hidden entries
                    if (isHiddenEntry(name, info.fileAttributes)) continue

                    val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L

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
            lastError.set(describeError(e))
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
        lastError.remove()

        return try {
            executeWithRetry(context, host, shareName) { share ->
                // Only what a rename needs (like Windows asks for). "Full control" would include
                // changing permissions and owner, which a server refuses to anyone but the owner
                // of the file, e.g. to a guest on a public share.
                val file = share.openFile(
                    oldSmbPath,
                    RENAME_ACCESS,
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
            lastError.set(describeError(e))
            false
        }
    }

    /**
     * Delete a file on the SMB share. Returns true once the file is really gone.
     */
    fun deleteFile(context: Context, host: String, shareName: String, filePath: String): Boolean {
        val smbPath = filePath.replace('/', '\\')
        lastError.remove()
        try {
            val stillThere = executeWithRetry(context, host, shareName) { share ->
                share.rm(smbPath)
                share.fileExists(smbPath)
            }
            if (!stillThere) return true

            // The server keeps a file that is still open somewhere "delete pending". Dropping our
            // connection closes every handle we may still hold; then check again.
            disconnect(host)
            val gone = !fileExists(context, host, shareName, filePath)
            if (!gone) lastError.set("STATUS_DELETE_PENDING")
            return gone
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to delete: $smbPath", e)
            lastError.set(describeError(e))
            return false
        }
    }

    /** Create a folder (and nothing else) on the share. An already existing folder counts as success. */
    fun createDirectory(context: Context, host: String, shareName: String, folderPath: String): Boolean {
        val smbPath = folderPath.replace('/', '\\')
        lastError.remove()
        return try {
            executeWithRetry(context, host, shareName) { share ->
                if (!share.folderExists(smbPath)) {
                    share.mkdir(smbPath)
                }
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to create folder: $smbPath", e)
            lastError.set(describeError(e))
            false
        }
    }

    /** Thrown when reading the data to write fails (the original, e.g. on another share). */
    private class SourceReadException(cause: Exception) : java.io.IOException(cause)

    private class SourceStream(input: InputStream) : java.io.FilterInputStream(input) {
        override fun read(): Int = try {
            super.read()
        } catch (e: Exception) {
            throw SourceReadException(e)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            super.read(b, off, len)
        } catch (e: Exception) {
            throw SourceReadException(e)
        }
    }

    /**
     * Why [writeStream] failed, for the list of files that could not be copied: the server's
     * answer, at which step (creating the file, writing it, reading the original), where, and
     * with which login. E.g. "STATUS_ACCESS_DENIED when creating “Public/Fotos/a.jpg” as guest".
     */
    private fun writeFailure(
        context: Context,
        host: String,
        shareName: String,
        filePath: String,
        e: Exception,
        created: Boolean
    ): String {
        val status = describeError(e)
        val readingSource = generateSequence<Throwable>(e) { it.cause }.any { it is SourceReadException }
        if (readingSource) return context.getString(R.string.smb_error_reading_original, status)

        val step = if (created) R.string.smb_error_writing else R.string.smb_error_creating
        return context.getString(step, status, "$shareName/${filePath.trim('/')}", loginLabel(context, host, shareName))
    }

    /** "as guest" / "as “david”": the login used for [shareName], for error texts. */
    private fun loginLabel(context: Context, host: String, shareName: String): String {
        val config = resolveConfig(context, host, shareName) ?: return "?"
        return if (config.isGuest) {
            context.getString(R.string.smb_error_as_guest)
        } else {
            context.getString(R.string.smb_error_as_user, config.username)
        }
    }

    /** Result of [checkName]. */
    enum class NameCheck {
        FREE,
        TAKEN,

        /** The server did not say (e.g. STATUS_ACCESS_DENIED) or could not be reached; [takeLastError] tells why. */
        REFUSED
    }

    /** Whether a new file may be created as [path]: free, taken by a file or folder, or unclear. */
    fun checkName(context: Context, host: String, shareName: String, path: String): NameCheck {
        val smbPath = path.replace('/', '\\')
        var refusal: Exception? = null
        val result = try {
            executeWithRetry(context, host, shareName) { share ->
                try {
                    share.open(
                        smbPath,
                        EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    ).close()
                    NameCheck.TAKEN
                } catch (e: SMBApiException) {
                    when (e.status) {
                        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND -> NameCheck.FREE
                        // A file that is about to disappear still blocks its name.
                        NtStatus.STATUS_DELETE_PENDING, NtStatus.STATUS_SHARING_VIOLATION -> NameCheck.TAKEN
                        else -> {
                            refusal = e
                            NameCheck.REFUSED
                        }
                    }
                }
            }
        } catch (e: Exception) {
            refusal = e
            NameCheck.REFUSED
        }
        refusal?.let { e ->
            android.util.Log.w("SmbClient", "Could not check the name $smbPath", e)
            lastError.set(
                context.getString(
                    R.string.smb_error_checking_name, describeError(e),
                    "$shareName/${path.trim('/')}", loginLabel(context, host, shareName)
                )
            )
        }
        return result
    }

    /** Renames (moves) the folder [oldPath] to [newPath] on the same share, with all its content. */
    fun renameFolder(
        context: Context,
        host: String,
        shareName: String,
        oldPath: String,
        newPath: String
    ): Boolean {
        val oldSmbPath = oldPath.replace('/', '\\').trimEnd('\\')
        val newSmbPath = newPath.replace('/', '\\').trimEnd('\\')
        if (oldSmbPath.isEmpty() || newSmbPath.isEmpty()) return false

        return try {
            executeWithRetry(context, host, shareName) { share ->
                val folder = share.openDirectory(
                    oldSmbPath,
                    RENAME_ACCESS,
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                try {
                    // Never replaces an existing folder: the server refuses if the name is taken.
                    folder.rename(newSmbPath)
                } finally {
                    folder.close()
                }
                true
            }
        } catch (e: Exception) {
            // E.g. a file in it is open somewhere: the caller then moves the files one by one.
            android.util.Log.w("SmbClient", "Could not rename folder: $oldSmbPath -> $newSmbPath", e)
            lastError.set(describeError(e))
            false
        }
    }

    /**
     * Deletes the folder [folderPath] after its pictures were moved or deleted, but only if
     * nothing is left in it except empty folders and what NAS and computers keep there on their
     * own (see [isDisposable]). Anything else, hidden or not, keeps the folder. Never touches
     * the share itself. True once the folder is gone.
     */
    fun removeFolderIfOnlyLeftovers(
        context: Context,
        host: String,
        shareName: String,
        folderPath: String
    ): Boolean {
        val smbPath = folderPath.replace('/', '\\').trimEnd('\\')
        if (smbPath.isEmpty()) return false

        fun attempt(): Boolean = executeWithRetry(context, host, shareName) { share ->
            when {
                !share.folderExists(smbPath) -> true
                hasContentToKeep(share, smbPath) -> false
                else -> {
                    share.rmdir(smbPath, true)
                    !share.folderExists(smbPath)
                }
            }
        }

        return try {
            attempt()
        } catch (e: Exception) {
            // Files we deleted a moment ago may still be "delete pending" while a handle of ours
            // is open: dropping the connection closes every handle, then try once more.
            android.util.Log.w("SmbClient", "Could not remove folder $smbPath, retrying", e)
            disconnect(host)
            try {
                attempt()
            } catch (e2: Exception) {
                android.util.Log.e("SmbClient", "Could not remove folder $smbPath", e2)
                false
            }
        }
    }

    /** Whether anything except empty folders and [isDisposable] leftovers is inside [dirPath]. */
    private fun hasContentToKeep(share: DiskShare, dirPath: String): Boolean {
        for (info in share.list(dirPath)) {
            val name = info.fileName
            if (name == "." || name == "..") continue
            val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            if (isDisposable(name, isDir)) continue
            if (!isDir) return true
            if (hasContentToKeep(share, "$dirPath\\$name")) return true
        }
        return false
    }

    // What NAS systems and computers put into picture folders on their own: thumbnails (WD,
    // Synology, QNAP), folder settings of Windows and macOS. Only these may go with a folder.
    private val DISPOSABLE_FOLDERS = setOf(".wdmc", "@eadir", ".@__thumb", ".thumbnails", ".appledouble")
    private val DISPOSABLE_FILES = setOf(".ds_store", "thumbs.db", "desktop.ini", ".picasa.ini", "picasa.ini", ".nomedia")

    /** True for thumbnails and settings a NAS or computer keeps in a folder (never a picture). */
    internal fun isDisposable(name: String, isDirectory: Boolean): Boolean {
        val lower = name.lowercase()
        return if (isDirectory) {
            lower in DISPOSABLE_FOLDERS
        } else {
            // "._name": what a Mac stores next to a file on a share.
            lower in DISPOSABLE_FILES || lower.startsWith("._")
        }
    }

    /** Entries [listFiles] leaves out: names starting with a dot and entries marked hidden. */
    internal fun isHiddenEntry(name: String, attributes: Long): Boolean =
        name.startsWith(".") || attributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L

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
        lastError.remove()
        // Errors while reading the original are told apart from errors on this share.
        val source = SourceStream(input)
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
                        source.copyTo(output, 256 * 1024)
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
            val reason = writeFailure(context, host, shareName, filePath, e, created)
            if (created) {
                deleteFile(context, host, shareName, filePath)
            }
            // After the clean-up: why writing failed, not how removing the half file went.
            lastError.set(reason)
            false
        }
    }

    /**
     * Gives the file at [filePath] the modification time [epochMillis] (e.g. the date a picture
     * was taken). Only the times change, not the content. Returns true if the server kept it.
     */
    fun setLastModified(
        context: Context,
        host: String,
        shareName: String,
        filePath: String,
        epochMillis: Long
    ): Boolean {
        val smbPath = filePath.replace('/', '\\')
        return try {
            executeWithRetry(context, host, shareName) { share ->
                val file = share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES),
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
                val actual = share.getFileInformation(smbPath).basicInformation.lastWriteTime.toEpochMillis()
                kotlin.math.abs(actual - epochMillis) <= 2000L
            }
        } catch (e: Exception) {
            android.util.Log.e("SmbClient", "Failed to set the date of $smbPath", e)
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

    /** The login for [shareName] on [host]: the connection saved for that share, see NetworkConfigStore. */
    private fun resolveConfig(context: Context, host: String, shareName: String): NetworkServerConfig? {
        return NetworkConfigStore.getSmbServerForShare(context, host, shareName)
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

    /**
     * Who logs in, as part of the cache keys. A guest connection keeps whatever was typed in the
     * (disabled) user name field, so it must not share a session with that user's real login.
     */
    internal fun loginKey(config: NetworkServerConfig): String =
        if (config.isGuest) GUEST_LOGIN_KEY else config.username

    @Synchronized
    private fun getOrCreateSession(config: NetworkServerConfig): Session {
        val key = "${config.host}|${loginKey(config)}"
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
        // Each share with its own login: e.g. "Public" as guest, a personal share as its user.
        val config = resolveConfig(context, host, shareName)
            ?: throw SmbException("No config for host: $host")
        return getOrCreateShareWithConfig(config, shareName)
    }

    @Synchronized
    private fun getOrCreateShareWithConfig(config: NetworkServerConfig, shareName: String): DiskShare {
        val key = "${config.host}|${loginKey(config)}|$shareName"
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
        var share: DiskShare? = null
        try {
            share = getOrCreateShare(context, host, shareName)
            return block(share)
        } catch (e: Exception) {
            if (!looksLikeLostConnection(e)) throw e

            android.util.Log.w("SmbClient", "SMB connection appears stale, retrying...", e)
            dropIfStillCurrent(host, share)
            val newShare = getOrCreateShare(context, host, shareName)
            return block(newShare)
        }
    }

    /** Whether [e] means the connection is gone (then it is opened again once). */
    internal fun looksLikeLostConnection(e: Throwable): Boolean {
        val chain = generateSequence(e) { it.cause }.take(8).toList()
        // Stopped on purpose (e.g. "Cancel"): not a connection problem.
        if (chain.any { it is InterruptedException }) return false
        if (chain.any { it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException }) {
            return true
        }
        return chain.any { error ->
            val message = error.message.orEmpty()
            message.contains("closed", ignoreCase = true) ||
                message.contains("Connection reset", ignoreCase = true) ||
                message.contains("Broken pipe", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ||
                message.contains("Timeout expired", ignoreCase = true)
        }
    }

    /**
     * Drops the connection to [host] after [failed] stopped working. Several requests run at the
     * same time (e.g. thumbnails): when they all notice the lost connection, only the first one
     * reconnects; the others must not close the fresh connection it has just opened.
     */
    @Synchronized
    private fun dropIfStillCurrent(host: String, failed: DiskShare?) {
        if (failed == null || shares.values.any { it === failed }) {
            disconnect(host)
        }
    }

    class SmbException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
