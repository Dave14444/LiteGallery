package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class NfsExportListerTest {

    private fun bytes(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun buildRpcCallHeader_encodesAuthNoneCall() {
        val header = NfsExportLister.buildRpcCallHeader(
            xid = 7, program = 100005, version = 3, procedure = 5
        )

        assertEquals(40, header.size)
        assertEquals(7, NfsExportLister.decodeUInt(header, 0))        // xid
        assertEquals(0, NfsExportLister.decodeUInt(header, 4))        // msg_type CALL
        assertEquals(2, NfsExportLister.decodeUInt(header, 8))        // RPC version
        assertEquals(100005, NfsExportLister.decodeUInt(header, 12))  // program
        assertEquals(3, NfsExportLister.decodeUInt(header, 16))       // version
        assertEquals(5, NfsExportLister.decodeUInt(header, 20))       // procedure
        assertEquals(0, NfsExportLister.decodeUInt(header, 24))       // cred flavor AUTH_NONE
        assertEquals(0, NfsExportLister.decodeUInt(header, 28))       // cred length
        assertEquals(0, NfsExportLister.decodeUInt(header, 32))       // verf flavor AUTH_NONE
        assertEquals(0, NfsExportLister.decodeUInt(header, 36))       // verf length
    }

    @Test
    fun encodeAndDecodeString_roundTripWithPadding() {
        val encoded = NfsExportLister.encodeString("/data")   // 5 bytes -> padded to 8

        assertEquals(12, encoded.size)                         // 4 length + 8 padded value
        val (value, next) = NfsExportLister.decodeString(encoded, 0)
        assertEquals("/data", value)
        assertEquals(12, next)
    }

    @Test
    fun parseExportBody_readsDirPathsAndSkipsGroups() {
        val body = bytes(
            NfsExportLister.encodeUInt(1),                    // entry follows
            NfsExportLister.encodeString("/volume1/photo"),
            NfsExportLister.encodeUInt(1),                    // group follows
            NfsExportLister.encodeString("*"),
            NfsExportLister.encodeUInt(0),                    // end of groups
            NfsExportLister.encodeUInt(1),                    // entry follows
            NfsExportLister.encodeString("/data"),
            NfsExportLister.encodeUInt(0),                    // end of groups
            NfsExportLister.encodeUInt(0)                     // end of entries
        )

        assertEquals(listOf("/volume1/photo", "/data"), NfsExportLister.parseExportBody(body))
    }

    @Test
    fun parseExportBody_emptyListIsValid() {
        assertEquals(
            emptyList<String>(),
            NfsExportLister.parseExportBody(NfsExportLister.encodeUInt(0))
        )
    }

    @Test
    fun stripReplyHeader_returnsBodyOfAcceptedSuccessReply() {
        val reply = bytes(
            NfsExportLister.encodeUInt(99),                   // xid
            NfsExportLister.encodeUInt(1),                    // REPLY
            NfsExportLister.encodeUInt(0),                    // MSG_ACCEPTED
            NfsExportLister.encodeUInt(0),                    // verf flavor
            NfsExportLister.encodeUInt(0),                    // verf length
            NfsExportLister.encodeUInt(0),                    // accept_stat SUCCESS
            NfsExportLister.encodeUInt(2049)                  // result body
        )

        val body = NfsExportLister.stripReplyHeader(reply)

        assertEquals(2049, NfsExportLister.decodeUInt(body, 0))
    }

    @Test
    fun stripReplyHeader_throwsOnDeniedOrTruncatedReply() {
        val denied = bytes(
            NfsExportLister.encodeUInt(99),
            NfsExportLister.encodeUInt(1),
            NfsExportLister.encodeUInt(1)                     // MSG_DENIED
        )
        assertThrows(NfsExportLister.ExportListException::class.java) {
            NfsExportLister.stripReplyHeader(denied)
        }
        assertThrows(NfsExportLister.ExportListException::class.java) {
            NfsExportLister.stripReplyHeader(NfsExportLister.encodeUInt(99))
        }
    }
}
