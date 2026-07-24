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
            // Sentinel path routed back to this source via handles(); kept as "smb://"
            // for compatibility with folder entries persisted by earlier versions.
            path = "smb://",
            itemCount = serverCount,
            thumbnail = null
        )
    }

    override fun browseIntent(context: Context): Intent =
        Intent(context, NetworkBrowseActivity::class.java)

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

    override fun shutdown() {
        RemoteClients.all().forEach { it.disconnectAll() }
    }
}
