package org.iurl.litegallery

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * How far a picture that is read over the network has come, for the progress display of the
 * viewer. The source that reads the data reports it (see [ProgressStream]); the viewer listens
 * for the picture it shows.
 */
object RemoteLoadProgress {

    /** Called on the thread that reads the data. [totalBytes] is -1 if unknown. */
    fun interface Listener {
        fun onProgress(bytesRead: Long, totalBytes: Long)
    }

    private val listeners = ConcurrentHashMap<String, Listener>()

    fun listen(path: String, listener: Listener) {
        listeners[path] = listener
    }

    fun stopListening(path: String, listener: Listener) {
        listeners.remove(path, listener)
    }

    fun report(path: String, bytesRead: Long, totalBytes: Long) {
        listeners[path]?.onProgress(bytesRead, totalBytes)
    }

    /** At most this often (and every [REPORT_EVERY_BYTES]) the progress is passed on. */
    private const val REPORT_EVERY_MS = 150L
    private const val REPORT_EVERY_BYTES = 256L * 1024L

    /**
     * Counts what is read from [input] and reports it for [path] (of [totalBytes], -1 if
     * unknown), not more often than needed for a smooth display.
     */
    class ProgressStream(
        input: InputStream,
        private val path: String,
        private val totalBytes: Long
    ) : FilterInputStream(input) {

        private var bytesRead = 0L
        private var reportedBytes = 0L
        private var reportedAt = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) counted(1) else finished()
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) counted(count.toLong()) else if (count < 0) finished()
            return count
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) counted(skipped)
            return skipped
        }

        private fun counted(bytes: Long) {
            bytesRead += bytes
            val now = System.currentTimeMillis()
            if (bytesRead - reportedBytes >= REPORT_EVERY_BYTES || now - reportedAt >= REPORT_EVERY_MS) {
                reportedBytes = bytesRead
                reportedAt = now
                report(path, bytesRead, totalBytes)
            }
        }

        private fun finished() {
            if (reportedBytes != bytesRead) {
                reportedBytes = bytesRead
                report(path, bytesRead, totalBytes)
            }
        }
    }
}
