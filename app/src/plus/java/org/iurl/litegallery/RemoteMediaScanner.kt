package org.iurl.litegallery

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Scans remote shares (SMB, NFS) for media files and folders via the RemoteClients seam.
 */
class RemoteMediaScanner(private val context: Context) {

    /**
     * Scan a remote directory for media items (files only, not recursive).
     */
    suspend fun scanMediaInFolder(folderUrl: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val client = RemoteClients.forUrl(folderUrl) ?: return@withContext emptyList()
        val items = mutableListOf<MediaItem>()

        try {
            for (file in client.listFiles(context, folderUrl)) {
                if (file.isDirectory || !file.isMedia) continue

                items.add(
                    MediaItem(
                        id = MediaItem.NO_MEDIASTORE_ID,
                        name = file.name,
                        path = file.url,
                        dateModified = file.lastModified,
                        size = file.size,
                        mimeType = RemoteFileTypes.mimeType(file.name),
                        duration = 0, // Cannot easily get duration without downloading
                        width = 0,
                        height = 0
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e(
                "RemoteMediaScanner",
                "Failed to scan remote media in folder: $folderUrl",
                e
            )
        }

        items.sortedByDescending { it.dateModified }
    }

    /**
     * Scan a remote directory directly to lightweight skeleton representations.
     */
    suspend fun scanSkeletonsInFolder(folderUrl: String): List<MediaItemSkeleton> =
        withContext(Dispatchers.IO) {
            val fullItems = scanMediaInFolder(folderUrl)
            fullItems.map { item ->
                MediaItemSkeleton(
                    id = item.id,
                    path = item.path,
                    name = item.name,
                    dateModified = item.dateModified,
                    size = item.size,
                    isVideo = item.isVideo
                )
            }
        }

    /**
     * Stream scanned remote media items chunk by chunk using a Coroutine Flow.
     */
    fun scanMediaInFolderStreamed(folderUrl: String): Flow<LoadEvent> = flow {
        val skeletons = scanSkeletonsInFolder(folderUrl)
        MediaScanner.streamSkeletonListLoadEvents(skeletons).collect { emit(it) }
    }

    /**
     * List directories and media files in a remote folder (for browsing).
     * Listing failures propagate so the browse UI can show the cause.
     */
    suspend fun listDirectory(folderUrl: String): List<RemoteFileInfo> =
        withContext(Dispatchers.IO) {
            val client = RemoteClients.forUrl(folderUrl)
                ?: throw java.io.IOException("Unsupported address: $folderUrl")
            client.listFiles(context, folderUrl)
                .sortedWith(
                    compareByDescending<RemoteFileInfo> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
        }
}
