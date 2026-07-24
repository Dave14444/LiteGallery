package org.iurl.litegallery

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Lists the exported directories of an NFS server — the equivalent of `showmount -e`.
 *
 * The EMC nfs-client library exposes no MOUNT EXPORT call, so this implements the
 * minimal subset of ONC RPC (RFC 5531) needed for two TCP calls:
 *  1. PORTMAP (program 100000 v2) GETPORT — find the MOUNT service TCP port.
 *  2. MOUNT (program 100005 v3) EXPORT — read the export list.
 * Both calls use AUTH_NONE and TCP record marking.
 */
object NfsExportLister {

    private const val PORTMAP_PORT = 111
    private const val PORTMAP_PROGRAM = 100000
    private const val PORTMAP_VERSION = 2
    private const val PORTMAP_PROC_GETPORT = 3
    private const val MOUNT_PROGRAM = 100005
    private const val MOUNT_VERSION = 3
    private const val MOUNT_PROC_EXPORT = 5
    private const val IPPROTO_TCP = 6
    private const val MSG_TYPE_CALL = 0
    private const val MSG_TYPE_REPLY = 1
    private const val REPLY_STAT_ACCEPTED = 0
    private const val ACCEPT_STAT_SUCCESS = 0
    private const val MAX_FRAGMENT_BYTES = 1 shl 20
    private val LAST_FRAGMENT = 0x80000000.toInt()

    class ExportListException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /**
     * Query [host] for its NFS export paths. Blocking network I/O — call from
     * Dispatchers.IO. Throws [ExportListException] when the server cannot be
     * reached or replies unexpectedly.
     */
    fun listExports(host: String, timeoutMs: Int = 5000): List<String> {
        val mountPort = queryMountPort(host, timeoutMs)
        if (mountPort <= 0 || mountPort > 65535) {
            throw ExportListException("MOUNT service not registered on $host")
        }
        val call = buildRpcCallHeader(
            Random.nextInt(),
            MOUNT_PROGRAM,
            MOUNT_VERSION,
            MOUNT_PROC_EXPORT
        )
        return parseExportBody(rpcCall(host, mountPort, timeoutMs, call))
    }

    private fun queryMountPort(host: String, timeoutMs: Int): Int {
        val call = buildRpcCallHeader(
            Random.nextInt(),
            PORTMAP_PROGRAM,
            PORTMAP_VERSION,
            PORTMAP_PROC_GETPORT
        ) +
            encodeUInt(MOUNT_PROGRAM) +
            encodeUInt(MOUNT_VERSION) +
            encodeUInt(IPPROTO_TCP) +
            encodeUInt(0)
        return decodeUInt(rpcCall(host, PORTMAP_PORT, timeoutMs, call), 0)
    }

    // --- XDR encoding/decoding (pure functions, unit tested) ---

    internal fun encodeUInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).putInt(value).array()

    internal fun decodeUInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) throw ExportListException("Truncated RPC reply")
        return ByteBuffer.wrap(bytes, offset, 4).int
    }

    /** Encode an XDR string: length prefix + bytes + zero padding to a 4-byte boundary. */
    internal fun encodeString(value: String): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        val padded = (raw.size + 3) / 4 * 4
        val out = ByteArray(4 + padded)
        System.arraycopy(encodeUInt(raw.size), 0, out, 0, 4)
        System.arraycopy(raw, 0, out, 4, raw.size)
        return out
    }

    /** Decode an XDR string. Returns the value and the offset right after its padding. */
    internal fun decodeString(bytes: ByteArray, offset: Int): Pair<String, Int> {
        val length = decodeUInt(bytes, offset)
        if (length < 0 || offset + 4 + length > bytes.size) {
            throw ExportListException("Truncated RPC string")
        }
        val value = String(bytes, offset + 4, length, Charsets.UTF_8)
        val padded = (length + 3) / 4 * 4
        return value to (offset + 4 + padded)
    }

    /** RPC call header with AUTH_NONE credential and verifier (RFC 5531 section 9). */
    internal fun buildRpcCallHeader(
        xid: Int,
        program: Int,
        version: Int,
        procedure: Int
    ): ByteArray =
        encodeUInt(xid) +
            encodeUInt(MSG_TYPE_CALL) +
            encodeUInt(2) +                    // RPC protocol version
            encodeUInt(program) +
            encodeUInt(version) +
            encodeUInt(procedure) +
            encodeUInt(0) + encodeUInt(0) +    // credential: AUTH_NONE, zero-length body
            encodeUInt(0) + encodeUInt(0)      // verifier: AUTH_NONE, zero-length body

    /**
     * Parse a MOUNT EXPORT reply body: an XDR linked list of {dirpath, group list}.
     * Returns the dirpaths; group entries are read past and discarded.
     */
    internal fun parseExportBody(body: ByteArray): List<String> {
        val exports = mutableListOf<String>()
        var offset = 0
        while (true) {
            val entryFollows = decodeUInt(body, offset)
            offset += 4
            if (entryFollows == 0) break
            val (dirPath, afterPath) = decodeString(body, offset)
            offset = afterPath
            exports.add(dirPath)
            while (true) {
                val groupFollows = decodeUInt(body, offset)
                offset += 4
                if (groupFollows == 0) break
                val (_, afterGroup) = decodeString(body, offset)
                offset = afterGroup
            }
        }
        return exports
    }

    /** Validate an RPC reply header and return the result body that follows it. */
    internal fun stripReplyHeader(reply: ByteArray): ByteArray {
        var offset = 4 // xid: one call per connection, not checked
        if (decodeUInt(reply, offset) != MSG_TYPE_REPLY) {
            throw ExportListException("Not an RPC reply")
        }
        offset += 4
        if (decodeUInt(reply, offset) != REPLY_STAT_ACCEPTED) {
            throw ExportListException("RPC call denied by server")
        }
        offset += 4
        offset += 4 // verifier flavor
        val verifierLength = decodeUInt(reply, offset)
        offset += 4 + (verifierLength + 3) / 4 * 4
        val acceptStat = decodeUInt(reply, offset)
        if (acceptStat != ACCEPT_STAT_SUCCESS) {
            throw ExportListException("RPC call failed with accept_stat $acceptStat")
        }
        offset += 4
        return reply.copyOfRange(offset, reply.size)
    }

    // --- TCP transport with record marking (RFC 5531 section 11) ---

    private fun rpcCall(host: String, port: Int, timeoutMs: Int, callBody: ByteArray): ByteArray {
        try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val out = DataOutputStream(socket.getOutputStream())
                out.writeInt(LAST_FRAGMENT or callBody.size)
                out.write(callBody)
                out.flush()
                return stripReplyHeader(readRecord(DataInputStream(socket.getInputStream())))
            }
        } catch (e: ExportListException) {
            throw e
        } catch (e: IOException) {
            throw ExportListException("RPC call to $host:$port failed: ${e.message}", e)
        }
    }

    private fun readRecord(input: DataInputStream): ByteArray {
        val record = java.io.ByteArrayOutputStream()
        while (true) {
            val marker = try {
                input.readInt()
            } catch (e: EOFException) {
                throw ExportListException("Connection closed by server", e)
            }
            val length = marker and LAST_FRAGMENT.inv()
            if (length < 0 || length > MAX_FRAGMENT_BYTES) {
                throw ExportListException("Unreasonable RPC fragment size: $length")
            }
            val chunk = ByteArray(length)
            input.readFully(chunk)
            record.write(chunk)
            if (marker and LAST_FRAGMENT != 0) break
        }
        return record.toByteArray()
    }
}
