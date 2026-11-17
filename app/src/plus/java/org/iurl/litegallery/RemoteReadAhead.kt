package org.iurl.litegallery

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Reads a video file on a network share for playback.
 *
 * While the player looks around in the file (header, index), small pieces are read, growing as
 * long as it reads straight on. Once it plays, several big pieces are asked for at the same time:
 * over the VPN every answer takes a while to come back, and one piece after the other would
 * leave the connection idle in between. With several pieces underway it stays busy, so the video
 * arrives as fast as the connection allows.
 */
internal class RemoteReadAhead(
    private val file: RemoteRandomAccessFile,
    /** Size of the file; below 0 if unknown. */
    private val fileSize: Long,
    /** Start with big pieces (the same video again, e.g. after a jump): playing goes on quickly. */
    startFast: Boolean = false,
    private val segmentSize: Int = SEGMENT_SIZE,
    private val inFlight: Int = IN_FLIGHT,
    private val executor: ExecutorService = sharedExecutor
) {

    /** What was read for one piece; [length] 0 means the end of the file. */
    private class Chunk(val data: ByteArray, val length: Int)

    private class Segment(val offset: Long, val result: Future<Chunk>)

    // Looking around: one piece at a time, read directly.
    private val probe = ByteArray(segmentSize)
    private var probeOffset = -1L
    private var probeLength = 0
    private var probeChunk = if (startFast) segmentSize else MIN_CHUNK

    // Playing: several pieces underway.
    private val segments = ArrayDeque<Segment>()
    private var nextOffset = 0L

    /** Whether several pieces are read at the same time now. */
    val isStreaming: Boolean get() = segments.isNotEmpty()

    /** Reads up to [length] bytes at [position] into [target]; -1 at the end of the file. */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (fileSize >= 0 && position >= fileSize) return -1

        if (segments.isNotEmpty()) {
            val read = readFromSegments(position, target, offset, length)
            if (read != NOT_THERE) return read
            // Jumped elsewhere: back to looking around.
            stopStreaming()
            probeChunk = MIN_CHUNK
        }

        if (probeOffset >= 0 && position >= probeOffset && position < probeOffset + probeLength) {
            val inProbe = (position - probeOffset).toInt()
            val count = minOf(length, probeLength - inProbe)
            System.arraycopy(probe, inProbe, target, offset, count)
            return count
        }

        val straightOn = probeOffset >= 0 && position == probeOffset + probeLength
        if (straightOn && probeChunk >= segmentSize) {
            startStreaming(position)
            val read = readFromSegments(position, target, offset, length)
            if (read != NOT_THERE) return read
            stopStreaming()
        }
        if (probeOffset >= 0) probeChunk = if (straightOn) minOf(probeChunk * 2, segmentSize) else MIN_CHUNK

        val wanted = if (fileSize >= 0) minOf(probeChunk.toLong(), fileSize - position).toInt() else probeChunk
        val read = file.readAt(position, probe, 0, wanted)
        if (read <= 0) return -1
        probeOffset = position
        probeLength = read
        val count = minOf(length, read)
        System.arraycopy(probe, 0, target, offset, count)
        return count
    }

    fun close() {
        stopStreaming()
    }

    private fun startStreaming(position: Long) {
        stopStreaming()
        nextOffset = position
        fill()
    }

    private fun stopStreaming() {
        segments.forEach { it.result.cancel(false) }
        segments.clear()
    }

    /** Keeps [inFlight] pieces underway (as far as the file goes). */
    private fun fill() {
        while (segments.size < inFlight && (fileSize < 0 || nextOffset < fileSize)) {
            val offset = nextOffset
            val length = if (fileSize >= 0) minOf(segmentSize.toLong(), fileSize - offset).toInt() else segmentSize
            segments.addLast(Segment(offset, executor.submit(Callable { readPiece(offset, length) })))
            nextOffset += segmentSize
        }
    }

    /** On a worker thread: one piece, as complete as the share gives it. */
    private fun readPiece(offset: Long, length: Int): Chunk {
        val data = ByteArray(length)
        var total = 0
        while (total < length) {
            val read = file.readAt(offset + total, data, total, length - total)
            if (read <= 0) break
            total += read
        }
        return Chunk(data, total)
    }

    /** From the pieces underway, or [NOT_THERE] if [position] is not among them. */
    private fun readFromSegments(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        val first = segments.firstOrNull() ?: return NOT_THERE
        if (position < first.offset || position >= nextOffset) return NOT_THERE
        // A small jump forward stays among the pieces underway: skip the ones before it.
        while (segments.isNotEmpty() && position >= segments.first().offset + segmentSize) {
            segments.removeFirst().result.cancel(false)
        }
        fill()
        val head = segments.firstOrNull() ?: return NOT_THERE

        val chunk = await(head.result)
        val inChunk = (position - head.offset).toInt()
        if (inChunk >= chunk.length) {
            // Less than asked for: the end of the file, or the rest is read directly.
            return if (chunk.length < segmentSize && fileSize < 0) -1 else NOT_THERE
        }
        val count = minOf(length, chunk.length - inChunk)
        System.arraycopy(chunk.data, inChunk, target, offset, count)
        if (inChunk + count >= chunk.length) {
            segments.removeFirst()
            fill()
        }
        return count
    }

    private fun await(result: Future<Chunk>): Chunk = try {
        result.get()
    } catch (e: InterruptedException) {
        // The player stopped this load (e.g. a jump): let it know as it expects.
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Read interrupted")
    } catch (e: CancellationException) {
        throw InterruptedIOException("Read cancelled")
    } catch (e: ExecutionException) {
        val cause = e.cause
        throw cause as? IOException ?: IOException(cause?.message ?: "Read failed", cause)
    }

    companion object {
        /** Size of one piece while playing (what one request to the share brings, see smbj). */
        const val SEGMENT_SIZE = 1024 * 1024

        /** Pieces underway at the same time while playing. */
        const val IN_FLIGHT = 6

        /** First piece while looking around in the file. */
        const val MIN_CHUNK = 64 * 1024

        private const val NOT_THERE = Int.MIN_VALUE

        private val sharedExecutor: ExecutorService = Executors.newFixedThreadPool(IN_FLIGHT * 2) { runnable ->
            Thread(runnable, "video-read-ahead").apply { isDaemon = true }
        }
    }
}
