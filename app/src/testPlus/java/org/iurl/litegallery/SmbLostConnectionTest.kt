package org.iurl.litegallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

class SmbLostConnectionTest {

    @Test
    fun closedOrResetConnectionsAreLost() {
        assertTrue(SmbClient.looksLikeLostConnection(IOException("Connection closed by peer")))
        assertTrue(SmbClient.looksLikeLostConnection(IOException("Connection reset")))
        assertTrue(SmbClient.looksLikeLostConnection(RuntimeException("x", IOException("Broken pipe"))))
    }

    @Test
    fun timeoutsCountAsLostConnection() {
        assertTrue(SmbClient.looksLikeLostConnection(RuntimeException("x", TimeoutException("Timeout expired"))))
        assertTrue(SmbClient.looksLikeLostConnection(IOException("y", SocketTimeoutException("Read timed out"))))
        assertTrue(SmbClient.looksLikeLostConnection(RuntimeException("Timeout expired")))
    }

    @Test
    fun cancellingIsNotALostConnection() {
        assertFalse(
            SmbClient.looksLikeLostConnection(
                RuntimeException("Timeout expired", InterruptedException())
            )
        )
    }

    @Test
    fun otherErrorsAreNotALostConnection() {
        assertFalse(SmbClient.looksLikeLostConnection(IOException("STATUS_ACCESS_DENIED")))
        assertFalse(SmbClient.looksLikeLostConnection(IllegalStateException("No config for host")))
    }
}
