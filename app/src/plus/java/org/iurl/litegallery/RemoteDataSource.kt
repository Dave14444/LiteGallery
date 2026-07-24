package org.iurl.litegallery

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * Custom ExoPlayer DataSource that reads from remote shares (SMB, NFS) via the
 * RemoteFileClient seam. Uses RemoteRandomAccessFile.readAt directly for proper
 * random access and seeking.
 */
class RemoteDataSource(private val context: Context) : BaseDataSource(/* isNetwork= */ true) {

    private var remoteFile: RemoteRandomAccessFile? = null
    private var bytesRemaining: Long = 0
    private var opened = false
    private var currentUri: Uri? = null
    private var currentFileOffset: Long = 0

    // Read-ahead buffer to eliminate small network reads
    private val readBuffer = ByteArray(4 * 1024 * 1024) // 4MB max for higher throughput
    private var bufferFileOffset: Long = -1L
    private var bufferValidLength: Int = 0
    private var currentChunkSize: Int = 65536 // Adaptive chunk size starting at 64KB
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

            // If switching to a new file, reset chunk size to 64KB (fast atom probing).
            // If it's the same file (e.g. seeking), preserve the chunk size to maintain high throughput.
            if (currentUri != uri) {
                currentChunkSize = 65536
            }
            currentUri = uri
            opened = true
            
            // Invalidate buffer and prepare for new reads
            bufferFileOffset = -1L
            bufferValidLength = 0
            
            transferStarted(dataSpec)
            return bytesRemaining

        } catch (e: Exception) {
            throw java.io.IOException("Failed to open remote file: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val file = remoteFile ?: return C.RESULT_END_OF_INPUT

        try {
            // 1. Try to serve from read-ahead buffer
            if (bufferFileOffset != -1L && currentFileOffset >= bufferFileOffset && currentFileOffset < bufferFileOffset + bufferValidLength) {
                val bufferOffset = (currentFileOffset - bufferFileOffset).toInt()
                val availableInBuffer = bufferValidLength - bufferOffset
                
                var bytesToCopy = minOf(length, availableInBuffer)
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesToCopy = minOf(bytesToCopy.toLong(), bytesRemaining).toInt()
                }
                
                System.arraycopy(readBuffer, bufferOffset, buffer, offset, bytesToCopy)
                
                currentFileOffset += bytesToCopy
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesRemaining -= bytesToCopy
                }
                bytesTransferred(bytesToCopy)
                return bytesToCopy
            }

            // 2. Buffer miss or exhausted, fetch new chunk from network
            
            // Adaptive chunk sizing: increase exponentially on sequential reads to maximize throughput,
            // but keep it small (64KB) for random seeks (atom probing) to minimize wasted network I/O.
            if (bufferFileOffset != -1L && currentFileOffset == bufferFileOffset + bufferValidLength.toLong()) {
                currentChunkSize = minOf(currentChunkSize * 2, readBuffer.size)
            } else {
                currentChunkSize = 65536
            }

            var bytesToReadFromNetwork = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                currentChunkSize
            } else {
                minOf(currentChunkSize.toLong(), bytesRemaining).toInt()
            }
            
            // Limit to readBuffer size (2MB) just in case
            bytesToReadFromNetwork = minOf(bytesToReadFromNetwork, readBuffer.size)
            
            val bytesRead = file.readAt(currentFileOffset, readBuffer, 0, bytesToReadFromNetwork)

            if (bytesRead < 0) {
                if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining > 0) {
                    throw java.io.EOFException("Premature EOF at offset $currentFileOffset, expected $bytesRemaining more bytes")
                }
                return C.RESULT_END_OF_INPUT
            }
            if (bytesRead == 0) {
                if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining > 0) {
                    throw java.io.EOFException("Premature zero-byte read at offset $currentFileOffset, expected $bytesRemaining more bytes")
                }
                return C.RESULT_END_OF_INPUT
            }

            // 3. Update buffer state
            bufferFileOffset = currentFileOffset
            bufferValidLength = bytesRead

            // 4. Serve the request from the newly filled buffer
            var bytesToCopy = minOf(length, bufferValidLength)
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesToCopy = minOf(bytesToCopy.toLong(), bytesRemaining).toInt()
            }
            
            System.arraycopy(readBuffer, 0, buffer, offset, bytesToCopy)
            
            currentFileOffset += bytesToCopy
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= bytesToCopy
            }
            bytesTransferred(bytesToCopy)
            return bytesToCopy

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
            remoteFile?.close()
        } catch (e: Exception) {
            android.util.Log.w("RemoteDataSource", "Error closing remote file", e)
        } finally {
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
