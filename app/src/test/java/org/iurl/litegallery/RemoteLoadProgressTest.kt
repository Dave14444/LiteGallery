package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RemoteLoadProgressTest {

    @Test
    fun readingAPicture_reportsUpToItsWholeSize() {
        val path = "smb://nas/Public/a.jpg"
        val data = ByteArray(1_000_000) { it.toByte() }
        val reports = mutableListOf<Pair<Long, Long>>()
        val listener = RemoteLoadProgress.Listener { read, total -> reports += read to total }
        RemoteLoadProgress.listen(path, listener)
        try {
            RemoteLoadProgress.ProgressStream(ByteArrayInputStream(data), path, data.size.toLong()).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (stream.read(buffer) >= 0) Unit
            }
        } finally {
            RemoteLoadProgress.stopListening(path, listener)
        }

        assertTrue(reports.isNotEmpty())
        assertEquals(data.size.toLong() to data.size.toLong(), reports.last())
        // Growing, never more than the picture.
        assertTrue(reports.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test
    fun otherPictures_doNotReachTheListener() {
        val reports = mutableListOf<Long>()
        val listener = RemoteLoadProgress.Listener { read, _ -> reports += read }
        RemoteLoadProgress.listen("smb://nas/Public/a.jpg", listener)
        try {
            RemoteLoadProgress.ProgressStream(ByteArrayInputStream(ByteArray(10)), "smb://nas/Public/b.jpg", 10L)
                .use { it.readBytes() }
        } finally {
            RemoteLoadProgress.stopListening("smb://nas/Public/a.jpg", listener)
        }
        assertTrue(reports.isEmpty())
    }
}
