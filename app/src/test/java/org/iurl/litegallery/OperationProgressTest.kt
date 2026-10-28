package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OperationProgressTest {

    @Test
    fun estimate_followsTheSpeedSoFar() {
        // 50 bytes in 5 seconds: the remaining 100 bytes take 10 seconds.
        assertEquals(10L, OperationProgress.estimateSecondsLeft(50L, 150L, 5_000L))
    }

    @Test
    fun estimate_roundsUpToWholeSeconds() {
        // 100 bytes in 4 seconds: 1 byte left takes 0.04 seconds, shown as 1 second.
        assertEquals(1L, OperationProgress.estimateSecondsLeft(100L, 101L, 4_000L))
    }

    @Test
    fun estimate_needsSomeDataAndTime() {
        assertNull(OperationProgress.estimateSecondsLeft(0L, 100L, 10_000L))
        assertNull(OperationProgress.estimateSecondsLeft(50L, 100L, 2_000L))
        assertNull(OperationProgress.estimateSecondsLeft(100L, 100L, 10_000L))
    }

    @Test
    fun counters_addUpAndIgnoreUnknownSizes() {
        OperationProgress.reset()
        OperationProgress.addTotal(1000L)
        OperationProgress.addTotal(-1L)
        OperationProgress.addDone(400L)
        OperationProgress.addDone(0L)

        assertEquals(1000L, OperationProgress.bytesTotal)
        assertEquals(400L, OperationProgress.bytesDone)

        OperationProgress.reset()
        assertEquals(0L, OperationProgress.bytesTotal)
        assertEquals(0L, OperationProgress.bytesDone)
    }
}
