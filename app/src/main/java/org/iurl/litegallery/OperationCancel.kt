package org.iurl.litegallery

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * "Cancel" flag for the long-running file operations (copy, move, delete, backup).
 *
 * Only one such operation runs at a time. The progress dialog calls [request]; the operation
 * checks [isRequested] between files and, while copying, between the chunks of a file.
 */
object OperationCancel {

    @Volatile
    private var requested = false

    /** Call before an operation starts. */
    fun reset() {
        requested = false
    }

    fun request() {
        requested = true
    }

    fun isRequested(): Boolean = requested
}

/** Thrown while copying when the user cancelled; the half-written copy is removed. */
class OperationCancelledException : IOException("Cancelled")

/**
 * Stops a running copy: every read fails once [OperationCancel.isRequested] is set. [onRead]
 * learns how many bytes were read (for the progress display).
 */
class CancellableInputStream(
    input: InputStream,
    private val onRead: ((Int) -> Unit)? = null
) : FilterInputStream(input) {

    private fun check() {
        if (OperationCancel.isRequested()) throw OperationCancelledException()
    }

    override fun read(): Int {
        check()
        val value = super.read()
        if (value >= 0) onRead?.invoke(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        check()
        val count = super.read(b, off, len)
        if (count > 0) onRead?.invoke(count)
        return count
    }
}

/**
 * How many bytes the running file operation has to copy and has copied so far. Like
 * [OperationCancel] there is one operation at a time; the progress dialog reads these values.
 */
object OperationProgress {

    @Volatile
    var bytesTotal = 0L
        private set

    @Volatile
    var bytesDone = 0L
        private set

    /** Call before an operation starts. */
    @Synchronized
    fun reset() {
        bytesTotal = 0L
        bytesDone = 0L
    }

    @Synchronized
    fun addTotal(bytes: Long) {
        if (bytes > 0L) bytesTotal += bytes
    }

    @Synchronized
    fun addDone(bytes: Long) {
        if (bytes > 0L) bytesDone += bytes
    }

    /**
     * Seconds until [total] bytes are reached at the speed so far, or null while there is not
     * enough to go by yet (the first seconds, or nothing copied).
     */
    fun estimateSecondsLeft(done: Long, total: Long, elapsedMs: Long): Long? {
        if (done <= 0L || done >= total || elapsedMs < MIN_ELAPSED_FOR_ESTIMATE_MS) return null
        // Whole numbers where possible, so the result does not depend on rounding of decimals.
        val remainingMs = try {
            Math.multiplyExact(total - done, elapsedMs) / done
        } catch (e: ArithmeticException) {
            ((total - done).toDouble() * elapsedMs / done).toLong()
        }
        return (remainingMs + 999L) / 1000L
    }

    private const val MIN_ELAPSED_FOR_ESTIMATE_MS = 3000L
}
