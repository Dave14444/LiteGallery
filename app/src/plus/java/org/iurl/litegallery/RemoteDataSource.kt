package org.iurl.litegallery

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * Custom ExoPlayer DataSource that reads from network shares (SMB) via the
 * RemoteFileClient seam, with random access for seeking. The reading itself (small pieces while
 * the player looks around, several big pieces at the same time while it plays) is done by
 * [RemoteReadAhead].
 */
class RemoteDataSource(private val context: Context) : BaseDataSource(/* isNetwork= */ true) {

    private var remoteFile: RemoteRandomAccessFile? = null
    private var reader: RemoteReadAhead? = null
    private var bytesRemaining: Long = 0
    private var opened = false
    private var currentUri: Uri? = null
    private var currentFileOffset: Long = 0

    /** The last reader of [currentUri] was playing (big pieces): after a jump it starts like that again. */
    private var wasStreaming = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val uri = dataSpec.uri
        val url = uri.toString()

        try {
            val client = RemoteClients.forUrl(url)
                ?: throw java.io.IOException("No remote client for URL: $url")
            val file = client.openRandomAccess(context, url)
            val fileSize = file.size
            remoteFile = file
            currentFileOffset = dataSpec.position

            // Calculate bytes remaining robustly. If fileSize is invalid or smaller than position,
            // we must not promise a specific length, otherwise ExoPlayer throws EOFException.
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                if (fileSize > dataSpec.position) {
                    minOf(dataSpec.length, fileSize - dataSpec.position)
                } else {
                    // We don't trust the fileSize, so we don't promise dataSpec.length.
                    C.LENGTH_UNSET.toLong()
                }
            } else if (fileSize > dataSpec.position) {
                fileSize - dataSpec.position
            } else {
                C.LENGTH_UNSET.toLong()
            }

            // Another video: start by looking around. The same one (a jump): go on playing quickly.
            if (currentUri != uri) wasStreaming = false
            reader = RemoteReadAhead(file, if (fileSize > 0) fileSize else -1L, startFast = wasStreaming)
            currentUri = uri
            opened = true

            transferStarted(dataSpec)
            return bytesRemaining

        } catch (e: Exception) {
            throw java.io.IOException("Failed to open remote file: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val reader = reader ?: return C.RESULT_END_OF_INPUT

        try {
            val wanted = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                length
            } else {
                minOf(length.toLong(), bytesRemaining).toInt()
            }
            val bytesRead = reader.read(currentFileOffset, buffer, offset, wanted)
            if (reader.isStreaming) wasStreaming = true

            if (bytesRead <= 0) {
                if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining > 0) {
                    throw java.io.EOFException("Premature EOF at offset $currentFileOffset, expected $bytesRemaining more bytes")
                }
                return C.RESULT_END_OF_INPUT
            }

            currentFileOffset += bytesRead
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= bytesRead
            }
            bytesTransferred(bytesRead)
            return bytesRead

        } catch (e: Exception) {
            if (e is java.io.InterruptedIOException || e is InterruptedException || e.cause is InterruptedException) {
                // Clear interrupted status and throw InterruptedIOException for better ExoPlayer handling
                Thread.interrupted()
                throw java.io.InterruptedIOException("Remote read interrupted")
            }
            throw java.io.IOException("Failed to read remote file: ${e.message}", e)
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val wasOpened = opened
        try {
            // Pieces still underway are not needed any more.
            reader?.close()
            remoteFile?.close()
        } catch (e: Exception) {
            android.util.Log.w("RemoteDataSource", "Error closing remote file", e)
        } finally {
            reader = null
            remoteFile = null
            // We intentionally do NOT set currentUri = null here so the next open() can detect seeks
            opened = false
            bytesRemaining = 0
            currentFileOffset = 0
            if (wasOpened) {
                transferEnded()
            }
        }
    }
}

/**
 * Factory for creating RemoteDataSource instances.
 */
class RemoteDataSourceFactory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return RemoteDataSource(context)
    }
}
