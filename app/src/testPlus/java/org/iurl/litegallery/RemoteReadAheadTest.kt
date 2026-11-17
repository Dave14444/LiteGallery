package org.iurl.litegallery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class RemoteReadAheadTest {

    /** A file on the share: answers at most [maxPerRequest] bytes, after [delayMs]. */
    private class FakeFile(
        val content: ByteArray,
        private val maxPerRequest: Int = RemoteReadAhead.SEGMENT_SIZE,
        private val delayMs: Long = 0,
        private val halfAnswers: Boolean = false
    ) : RemoteRandomAccessFile {
        val running = AtomicInteger()
        val mostAtOnce = AtomicInteger()
        val requests = AtomicInteger()

        override val size: Long = content.size.toLong()

        override fun readAt(fileOffset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            requests.incrementAndGet()
            val now = running.incrementAndGet()
            mostAtOnce.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                if (delayMs > 0) Thread.sleep(delayMs)
                if (fileOffset >= content.size) return -1
                var count = minOf(length, maxPerRequest, content.size - fileOffset.toInt())
                if (halfAnswers && count > 1) count /= 2
                System.arraycopy(content, fileOffset.toInt(), buffer, bufferOffset, count)
                return count
            } finally {
                running.decrementAndGet()
            }
        }

        override fun close() {}
    }

    private fun content(size: Int) = Random(42).nextBytes(size)

    /** Reads everything from [start] like the player does (pieces of varying size). */
    private fun readAll(reader: RemoteReadAhead, size: Int, start: Long = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(200_000)
        var position = start
        var step = 0
        while (true) {
            val length = listOf(4096, 65_536, 188, 200_000, 33_333)[step++ % 5]
            val read = reader.read(position, buffer, 0, length)
            if (read < 0) break
            assertTrue(read > 0)
            out.write(buffer, 0, read)
            position += read
            assertTrue("read past the end", position <= size)
        }
        return out.toByteArray()
    }

    @Test
    fun playingStraightThrough_givesExactlyTheFile() {
        val data = content(9_500_000)
        val file = FakeFile(data)
        val reader = RemoteReadAhead(file, data.size.toLong())

        assertArrayEquals(data, readAll(reader, data.size))
        reader.close()
    }

    @Test
    fun whilePlaying_severalPiecesAreUnderwayAtTheSameTime() {
        val data = content(12_000_000)
        val file = FakeFile(data, delayMs = 30)
        val reader = RemoteReadAhead(file, data.size.toLong())

        assertArrayEquals(data, readAll(reader, data.size))
        assertTrue("only ${file.mostAtOnce.get()} at once", file.mostAtOnce.get() >= 3)
        reader.close()
    }

    @Test
    fun jumpsBackAndForth_stillGiveTheRightBytes() {
        val data = content(8_000_000)
        val reader = RemoteReadAhead(FakeFile(data), data.size.toLong())
        val buffer = ByteArray(100_000)

        // Warm up to playing, then jump around (inside and outside the pieces underway).
        readAll(reader, data.size)
        for (position in listOf(5_000_000L, 5_100_000L, 7_999_000L, 100L, 3_000_000L, 6_500_000L)) {
            val read = reader.read(position, buffer, 0, buffer.size)
            assertTrue(read > 0)
            assertArrayEquals(
                data.copyOfRange(position.toInt(), position.toInt() + read),
                buffer.copyOf(read)
            )
        }
        reader.close()
    }

    @Test
    fun aSmallJumpForwardWhilePlaying_usesThePiecesUnderway() {
        val data = content(10_000_000)
        val file = FakeFile(data)
        val reader = RemoteReadAhead(file, data.size.toLong())
        val buffer = ByteArray(65_536)
        var position = 0L
        while (position < 3_000_000) {
            position += reader.read(position, buffer, 0, buffer.size)
        }
        assertTrue(reader.isStreaming)

        val jumpTo = position + 1_500_000
        val read = reader.read(jumpTo, buffer, 0, buffer.size)
        assertArrayEquals(data.copyOfRange(jumpTo.toInt(), jumpTo.toInt() + read), buffer.copyOf(read))
        assertTrue(reader.isStreaming)
        reader.close()
    }

    @Test
    fun shortAnswersFromTheShare_areCompleted() {
        val data = content(5_000_000)
        val reader = RemoteReadAhead(FakeFile(data, halfAnswers = true), data.size.toLong())

        assertArrayEquals(data, readAll(reader, data.size))
        reader.close()
    }

    @Test
    fun withUnknownSize_theEndIsStillFound() {
        val data = content(3_300_000)
        val reader = RemoteReadAhead(FakeFile(data), fileSize = -1L)

        assertArrayEquals(data, readAll(reader, data.size))
        reader.close()
    }

    @Test
    fun theSameVideoAgain_startsWithBigPieces() {
        val data = content(4_000_000)
        val slow = RemoteReadAhead(FakeFile(data), data.size.toLong())
        val buffer = ByteArray(4096)
        slow.read(0, buffer, 0, buffer.size)
        slow.read(4096, buffer, 0, buffer.size)
        assertFalse(slow.isStreaming)

        val fast = RemoteReadAhead(FakeFile(data), data.size.toLong(), startFast = true)
        fast.read(0, buffer, 0, buffer.size)
        // The first piece was a big one: reading on from its end starts playing.
        val read = fast.read(RemoteReadAhead.SEGMENT_SIZE.toLong(), buffer, 0, buffer.size)
        assertEquals(buffer.size, read)
        assertTrue(fast.isStreaming)
        slow.close()
        fast.close()
    }
}
