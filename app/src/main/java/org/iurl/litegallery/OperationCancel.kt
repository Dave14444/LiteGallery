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

/** Stops a running copy: every read fails once [OperationCancel.isRequested] is set. */
class CancellableInputStream(input: InputStream) : FilterInputStream(input) {

    private fun check() {
        if (OperationCancel.isRequested()) throw OperationCancelledException()
    }

    override fun read(): Int {
        check()
        return super.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        check()
        return super.read(b, off, len)
    }
}
