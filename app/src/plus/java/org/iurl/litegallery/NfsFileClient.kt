package org.iurl.litegallery

import android.content.Context
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * [RemoteFileClient] for NFSv3 servers, backed by the EMC nfs-client library.
 *
 * URLs look like nfs://host/volume1/photo/sub/file.jpg — the export path and the
 * path inside the export are joined. The export boundary cannot be derived from
 * the URL alone, so it is resolved against the saved NFS server configs for the
 * host (longest matching export prefix, see [NfsPath.matchExport]).
 *
 * Mounting an export costs several RPC round trips (portmapper + MOUNT + FSINFO),
 * so mounted [Nfs3] handles are cached per host/export/identity. NFSv3 reads are
 * stateless, which is why [RemoteRandomAccessFile.close] has nothing to release.
 */
object NfsFileClient : RemoteFileClient {

    private const val MAX_RETRIES = 2

    // Cache: "host|export|uid|gid" -> mounted Nfs3 handle
    private val mounts = ConcurrentHashMap<String, Nfs3>()

    class NfsException(message: String, cause: Throwable? = null) : IOException(message, cause)

    internal data class Target(
        val config: NetworkServerConfig,
        val export: String,
        val innerPath: String
    )

    internal fun resolveTarget(context: Context, url: String): Target {
        val nfsPath = NfsPath.parse(url) ?: throw NfsException("Invalid NFS URL: $url")
        val configs = NetworkConfigStore.getAllServers(context)
            .filter { it.isNfs && it.host.equals(nfsPath.host, ignoreCase = true) }
        if (configs.isEmpty()) {
            throw NfsException("No saved NFS server for host: ${nfsPath.host}")
        }
        val export = NfsPath.matchExport(configs.map { it.export }, nfsPath.fullPath)
            ?: throw NfsException(
                "No saved export matches ${nfsPath.fullPath} on ${nfsPath.host}"
            )
        val config = configs.first { it.export == export }
        val innerPath = nfsPath.innerPath(export)
            ?: throw NfsException("Path ${nfsPath.fullPath} is outside export $export")
        return Target(config, export, innerPath)
    }

    private fun mountKey(config: NetworkServerConfig): String =
        "${config.host}|${config.export}|${config.uid}|${config.gid}"

    @Synchronized
    private fun getOrCreateMount(config: NetworkServerConfig): Nfs3 {
        mounts[mountKey(config)]?.let { return it }
        val nfs = Nfs3(
            config.host,
            config.export,
            CredentialUnix(config.uid, config.gid, emptySet()),
            MAX_RETRIES
        )
        mounts[mountKey(config)] = nfs
        return nfs
    }

    /**
     * Run [block] on the file at [url]. If the first attempt fails with an
     * IOException the cached mount is dropped and the call retried once with a
     * fresh mount, mirroring SmbClient.executeWithRetry.
     */
    private fun <T> withFile(context: Context, url: String, block: (Nfs3File) -> T): T {
        val target = resolveTarget(context, url)
        return try {
            block(Nfs3File(getOrCreateMount(target.config), target.innerPath))
        } catch (e: NfsException) {
            throw e
        } catch (e: IOException) {
            android.util.Log.w("NfsFileClient", "NFS mount appears stale, retrying...", e)
            mounts.remove(mountKey(target.config))
            block(Nfs3File(getOrCreateMount(target.config), target.innerPath))
        }
    }

    override fun listFiles(context: Context, folderUrl: String): List<RemoteFileInfo> {
        val target = resolveTarget(context, folderUrl)
        try {
            return withFile(context, folderUrl) { dir ->
                dir.listFiles().mapNotNull { child ->
                    val name = child.name
                    if (name.startsWith(".")) return@mapNotNull null
                    val isDirectory = child.isDirectory
                    RemoteFileInfo(
                        name = name,
                        url = NfsPath.toUrl(target.config.host, target.export + child.path),
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0L else child.length(),
                        lastModified = child.lastModified(),
                        isMedia = !isDirectory && RemoteFileTypes.isMedia(name),
                        isVideo = !isDirectory && RemoteFileTypes.isVideo(name)
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NfsFileClient", "Failed to list files: $folderUrl", e)
            throw if (e is NfsException) {
                e
            } else {
                NfsException("Failed to list files: ${e.message}", e)
            }
        }
    }

    override fun openInputStream(context: Context, fileUrl: String): InputStream =
        withFile(context, fileUrl) { file -> NfsFileInputStream(file) }

    override fun openRandomAccess(
        context: Context,
        fileUrl: String
    ): RemoteRandomAccessFile =
        withFile(context, fileUrl) { file ->
            val fileSize = file.lengthEx()
            object : RemoteRandomAccessFile {
                override val size: Long = fileSize

                override fun readAt(
                    fileOffset: Long,
                    buffer: ByteArray,
                    bufferOffset: Int,
                    length: Int
                ): Int {
                    val response = file.read(fileOffset, length, buffer, bufferOffset)
                    val bytesRead = response.bytesRead
                    return if (bytesRead <= 0 && response.isEof) -1 else bytesRead
                }

                override fun close() {
                    // NFSv3 reads are stateless: no handle to release.
                }
            }
        }

    override fun getFileSize(context: Context, fileUrl: String): Long = try {
        withFile(context, fileUrl) { it.lengthEx() }
    } catch (e: Exception) {
        android.util.Log.e("NfsFileClient", "Failed to get file size: $fileUrl", e)
        -1L
    }

    override fun rename(context: Context, oldUrl: String, newUrl: String): Boolean = try {
        val oldTarget = resolveTarget(context, oldUrl)
        val newTarget = resolveTarget(context, newUrl)
        if (!oldTarget.config.host.equals(newTarget.config.host, ignoreCase = true) ||
            oldTarget.export != newTarget.export
        ) {
            false
        } else {
            val mount = getOrCreateMount(oldTarget.config)
            Nfs3File(mount, oldTarget.innerPath)
                .renameTo(Nfs3File(mount, newTarget.innerPath))
        }
    } catch (e: Exception) {
        android.util.Log.e("NfsFileClient", "Failed to rename: $oldUrl -> $newUrl", e)
        false
    }

    override fun fileExists(context: Context, fileUrl: String): Boolean = try {
        withFile(context, fileUrl) { it.exists() }
    } catch (e: Exception) {
        false
    }

    override fun testConnection(config: NetworkServerConfig): Boolean = try {
        // Constructing Nfs3 performs the mount (portmapper + MOUNT + FSINFO);
        // nullCall additionally proves the NFS program answers.
        Nfs3(
            config.host,
            config.export,
            CredentialUnix(config.uid, config.gid, emptySet()),
            1
        ).nullCall()
        true
    } catch (e: Exception) {
        android.util.Log.e(
            "NfsFileClient",
            "Connection test failed for ${config.host}:${config.export}",
            e
        )
        false
    }

    override fun disconnect(host: String) {
        mounts.keys.filter { it.startsWith("$host|") }.forEach { mounts.remove(it) }
    }

    override fun disconnectAll() {
        mounts.clear()
    }
}
