package org.iurl.litegallery

import android.content.Context
import android.content.Intent
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [MediaSource] implementation for network shares (SMB and NFS).
 *
 * Only present in the `plus` flavor. It works through the protocol-agnostic
 * [RemoteClients] seam and is registered with [MediaSourceRegistry] at startup
 * by `MediaSourceBootstrap`.
 */
object NetworkMediaSource : MediaSource {

    override fun handles(path: String): Boolean = RemotePath.isRemote(path)

    override fun homeEntry(context: Context): MediaFolder {
        val serverCount = NetworkConfigStore.getAllServers(context).size
        return MediaFolder(
            name = context.getString(R.string.network_browse_title),
            // Sentinel path for the network browse hub.
            path = "smb://",
            itemCount = serverCount,
            thumbnail = null
        )
    }

    override fun homeEntries(context: Context): List<MediaFolder> {
        val networkHub = homeEntry(context)

        val savedConnections = NetworkConfigStore.getAllServers(context)
            .map { server ->
                MediaFolder(
                    name = server.displayName.ifBlank { server.host },
                    path = server.rootUrl(),
                    itemCount = 0,
                    thumbnail = SmbCoverImages.coverPath(context, server.id)
                )
            }

        return listOf(networkHub) + savedConnections
    }

    override fun browseIntent(context: Context): Intent =
        Intent(context, NetworkBrowseActivity::class.java)

    override fun browseIntent(context: Context, path: String): Intent =
        Intent(context, NetworkBrowseActivity::class.java).apply {
            if (path != "smb://") {
                putExtra(NetworkBrowseActivity.EXTRA_START_PATH, path)
            }
        }

    override val folderIconRes: Int = R.drawable.ic_network

    override fun canLoadThumbnail(path: String): Boolean = RemoteModelLoader.isRemoteImage(path)

    override suspend fun scanFolder(context: Context, folderPath: String): List<MediaItem> =
        RemoteMediaScanner(context).scanMediaInFolder(folderPath)

    override fun scanFolderStreamed(context: Context, folderPath: String): Flow<LoadEvent> =
        RemoteMediaScanner(context).scanMediaInFolderStreamed(folderPath)

    override fun createExoMediaSource(
        context: Context,
        path: String
    ): androidx.media3.exoplayer.source.MediaSource {
        val dataSourceFactory = RemoteDataSourceFactory(context)
        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(
                androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(path))
            )
    }

    override suspend fun rename(
        context: Context,
        path: String,
        newBaseName: String,
        extension: String
    ): RenameOutcome = withContext(Dispatchers.IO) {
        val client = RemoteClients.forUrl(path) ?: return@withContext RenameOutcome.Failed
        val parentUrl = path.substringBeforeLast('/', "")
        if (parentUrl.isBlank()) return@withContext RenameOutcome.Failed
        val newFileName = if (extension.isNotEmpty()) "$newBaseName.$extension" else newBaseName
        val newUrl = "$parentUrl/$newFileName"

        when {
            client.fileExists(context, newUrl) -> RenameOutcome.AlreadyExists
            client.rename(context, path, newUrl) -> RenameOutcome.Success(newUrl)
            else -> RenameOutcome.Failed
        }
    }
    override suspend fun copyToCache(
        context: Context,
        path: String,
        destination: java.io.File
    ): Boolean = withContext(Dispatchers.IO) {
        val client = RemoteClients.forUrl(path) ?: return@withContext false

        try {
            destination.parentFile?.mkdirs()
            client.openInputStream(context, path).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output, 256 * 1024)
                }
            }
            destination.isFile && destination.length() > 0L
        } catch (e: Exception) {
            destination.delete()
            android.util.Log.e("NetworkMediaSource", "Failed to copy remote media for sharing", e)
            false
        }
    }

    // ---- File operations (SMB only) ----

    override fun supportsFileOperations(path: String): Boolean = SmbPath.isSmb(path)

    override fun destinationRoots(context: Context): List<FolderEntry> =
        NetworkConfigStore.getAllServers(context)
            .filter { server -> !server.isNfs && server.share.isNotBlank() }
            .map { server ->
                FolderEntry(server.displayName.ifBlank { server.host }, server.rootUrl())
            }

    override fun listSubFolders(context: Context, folderPath: String): List<FolderEntry> =
        SmbFileClient.listFiles(context, folderPath)
            .filter { it.isDirectory }
            .map { FolderEntry(it.name, it.url) }

    override fun parentFolder(path: String): String? {
        val smb = SmbPath.parse(path) ?: return null
        return if (smb.path.isEmpty()) null else smb.parentFolderPath
    }

    override fun createFolder(context: Context, parentPath: String, name: String): String? {
        val parent = SmbPath.parse(parentPath) ?: return null
        val relative = if (parent.path.isEmpty()) name else "${parent.path}/$name"
        return if (SmbClient.createDirectory(context, parent.host, parent.share, relative)) {
            SmbPath.toUrl(parent.host, parent.share, relative)
        } else {
            null
        }
    }

    override fun openInput(context: Context, path: String): java.io.InputStream =
        SmbFileClient.openInputStream(context, path)

    override fun lastModified(context: Context, path: String): Long {
        val known = SmbFileMetaCache.get(path)
        if (known != null && known.lastModified > 0L) return known.lastModified
        val smb = SmbPath.parse(path) ?: return 0L
        val (_, modified) = SmbClient.getFileSizeAndLastModified(context, smb.host, smb.share, smb.path)
        return modified.coerceAtLeast(0L)
    }

    /** Asks the server (not the list cache), so a half-written file is noticed. */
    override fun fileSize(context: Context, path: String): Long {
        val smb = SmbPath.parse(path) ?: return -1L
        return SmbClient.getFileSizeAndLastModified(context, smb.host, smb.share, smb.path).first
    }

    override fun videoDurationMs(context: Context, path: String): Long =
        SmbThumbnailCache.videoDuration(context, path)

    override fun writeNew(
        context: Context,
        folderPath: String,
        desiredName: String,
        input: java.io.InputStream,
        lastModified: Long
    ): String? {
        val folder = SmbPath.parse(folderPath) ?: return null
        val relative = uniqueRelativePath(context, folder, desiredName) ?: return null
        val written = SmbClient.writeStream(
            context, folder.host, folder.share, relative, input, lastModified
        )
        return if (written) SmbPath.toUrl(folder.host, folder.share, relative) else null
    }

    override fun supportsTrash(path: String): Boolean = SmbPath.isSmb(path)

    override fun moveToTrash(context: Context, path: String): Boolean =
        SmbTrash.moveToTrash(context, path)

    override fun trashLocations(context: Context): List<TrashLocation> =
        NetworkConfigStore.getAllServers(context)
            .filter { server -> !server.isNfs && server.share.isNotBlank() }
            .map { server ->
                TrashLocation(server.displayName.ifBlank { server.host }) { activity ->
                    activity.startActivity(
                        android.content.Intent(activity, NetworkTrashActivity::class.java)
                            .putExtra(NetworkTrashActivity.EXTRA_SHARE_URL, server.rootUrl())
                    )
                }
            }

    override fun replaceFile(
        context: Context,
        path: String,
        input: java.io.InputStream,
        lastModified: Long
    ): Boolean {
        val smb = SmbPath.parse(path) ?: return false

        // Write the new content next to the original first, so the original survives a failure.
        val parent = smb.path.substringBeforeLast('/', "")
        val fileName = smb.path.substringAfterLast('/')
        val tempName = ".$fileName.editing-${System.currentTimeMillis()}"
        val tempPath = if (parent.isEmpty()) tempName else "$parent/$tempName"

        if (!SmbClient.writeStream(context, smb.host, smb.share, tempPath, input, lastModified)) {
            return false
        }
        if (!SmbClient.deleteFile(context, smb.host, smb.share, smb.path)) {
            SmbClient.deleteFile(context, smb.host, smb.share, tempPath)
            return false
        }
        if (!SmbClient.rename(context, smb.host, smb.share, tempPath, smb.path)) {
            android.util.Log.e("NetworkMediaSource", "Edited picture kept as $tempPath")
            return false
        }
        SmbFileMetaCache.remove(path)
        return true
    }

    override fun deleteFile(context: Context, path: String): Boolean {
        val smb = SmbPath.parse(path) ?: return false
        val deleted = SmbClient.deleteFile(context, smb.host, smb.share, smb.path)
        if (deleted) SmbFileMetaCache.remove(path)
        return deleted
    }

    override fun moveWithin(
        context: Context,
        path: String,
        destFolderPath: String,
        desiredName: String
    ): String? {
        val source = SmbPath.parse(path) ?: return null
        val folder = SmbPath.parse(destFolderPath) ?: return null
        val sameShare = source.host.equals(folder.host, ignoreCase = true) &&
            source.share.equals(folder.share, ignoreCase = true)
        if (!sameShare) return null

        val relative = uniqueRelativePath(context, folder, desiredName) ?: return null
        if (!SmbClient.rename(context, source.host, source.share, source.path, relative)) return null
        SmbFileMetaCache.remove(path)
        return SmbPath.toUrl(folder.host, folder.share, relative)
    }

    /** [fileName] inside [folder], or "name (1).ext", ... if the name is already taken. */
    private fun uniqueRelativePath(context: Context, folder: SmbPath, fileName: String): String? {
        fun pathFor(name: String) = if (folder.path.isEmpty()) name else "${folder.path}/$name"

        if (!SmbClient.fileExists(context, folder.host, folder.share, pathFor(fileName))) {
            return pathFor(fileName)
        }
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        for (i in 1..999) {
            val name = if (extension.isEmpty()) "$base ($i)" else "$base ($i).$extension"
            if (!SmbClient.fileExists(context, folder.host, folder.share, pathFor(name))) {
                return pathFor(name)
            }
        }
        return null
    }

    override fun showHomeEntryOptions(
        activity: androidx.appcompat.app.AppCompatActivity,
        folder: MediaFolder,
        onHide: () -> Unit,
        onChanged: () -> Unit
    ): Boolean {
        val server = NetworkConfigStore.getAllServers(activity)
            .firstOrNull { it.rootUrl() == folder.path }
            ?: return false
        SmbCoverImages.showOptions(activity, server, onHide, onChanged)
        return true
    }

    // ---- Folder screen ----

    override fun subFolderCards(context: Context, folderPath: String): List<MediaFolder> {
        val client = RemoteClients.forUrl(folderPath) ?: return emptyList()
        return client.listFiles(context, folderPath)
            .filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
            .map { folder ->
                val card = MediaFolder(
                    name = folder.name,
                    path = folder.url,
                    itemCount = -1,
                    isSubFolder = true
                )
                SmbFolderSummaries.cached(context, folder.url)
                    ?.let { SmbFolderSummaries.applyTo(card, it) }
                    ?: card
            }
    }

    override fun refreshSubFolderCard(context: Context, card: MediaFolder): MediaFolder? =
        SmbFolderSummaries.refresh(context, card.path)?.let { SmbFolderSummaries.applyTo(card, it) }

    override fun folderMenuActions(context: Context, folderPath: String): List<FolderAction> {
        if (!SmbPath.isSmb(folderPath)) return emptyList()
        val label = folderPath.trimEnd('/').substringAfterLast('/')
        val actions = mutableListOf<FolderAction>()
        SmbTrash.shareRootOf(folderPath)?.let { shareRoot ->
            actions.add(
                FolderAction(context.getString(R.string.trash_bin_title)) { activity ->
                    activity.startActivity(
                        Intent(activity, NetworkTrashActivity::class.java)
                            .putExtra(NetworkTrashActivity.EXTRA_SHARE_URL, shareRoot)
                    )
                }
            )
        }
        actions.add(
            FolderAction(context.getString(R.string.thumb_prepare_menu)) { activity ->
                ThumbnailPreparer.start(activity, folderPath, label)
            }
        )
        actions.add(
            FolderAction(context.getString(R.string.thumb_cleanup_menu)) { activity ->
                ThumbnailCleanup.start(activity, folderPath)
            }
        )
        return actions
    }

    override fun shutdown() {
        RemoteClients.all().forEach { it.disconnectAll() }
    }
}
