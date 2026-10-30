package org.iurl.litegallery

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.Flow

/**
 * A pluggable provider for media that does not live in the local device storage /
 * MediaStore — for example an SMB network share.
 *
 * The core app compiles only against this seam. Concrete implementations live in
 * optional build flavors (e.g. `plus`) and register themselves with
 * [MediaSourceRegistry] at startup via `MediaSourceBootstrap`. The `core` flavor
 * registers none, so it ships with no network capability whatsoever.
 */
interface MediaSource {

    /** True if [path] belongs to this source (e.g. it starts with `smb://`). */
    fun handles(path: String): Boolean

    /** A virtual home-screen folder representing this source, or null to show none. */
    fun homeEntry(context: Context): MediaFolder?

    /** All home-screen entries of this source (e.g. one per saved connection). */
    fun homeEntries(context: Context): List<MediaFolder> =
        listOfNotNull(homeEntry(context))

    /** Intent that opens this source's browse UI at [path]. */
    fun browseIntent(context: Context, path: String): Intent =
        browseIntent(context)

    /** Intent that opens this source's browse UI. */
    fun browseIntent(context: Context): Intent

    /** Drawable resource used as the folder icon for this source on the home screen. */
    val folderIconRes: Int

    /** Whether a thumbnail can be loaded (via Glide) for the given media [path]. */
    fun canLoadThumbnail(path: String): Boolean

    /** Scan a folder owned by this source into full media items. */
    suspend fun scanFolder(context: Context, folderPath: String): List<MediaItem>

    /** Stream-scan a folder owned by this source, chunk by chunk. */
    fun scanFolderStreamed(context: Context, folderPath: String): Flow<LoadEvent>

    /** Build a Media3 media source for streaming the video [path] owned by this source. */
    fun createExoMediaSource(
        context: Context,
        path: String
    ): androidx.media3.exoplayer.source.MediaSource

    /** Rename a file owned by this source. */
    suspend fun rename(
        context: Context,
        path: String,
        newBaseName: String,
        extension: String
    ): RenameOutcome

    /**
     * Copy a remote media file to a local destination for sharing.
     * Returns false if exporting the file is not supported or fails.
     */
    suspend fun copyToCache(
        context: Context,
        path: String,
        destination: java.io.File
    ): Boolean = false

    // ---- File operations (copy / move / delete / new folder) ----
    // All functions below do blocking I/O: call them from a background thread.

    /** True if files at [path] can be copied, moved, deleted and written by this source. */
    fun supportsFileOperations(path: String): Boolean = false

    /** Top-level destinations offered in the destination picker (e.g. the saved network shares). */
    fun destinationRoots(context: Context): List<FolderEntry> = emptyList()

    /** Sub folders of [folderPath]. */
    fun listSubFolders(context: Context, folderPath: String): List<FolderEntry> = emptyList()

    /** Parent of [path] inside this source, or null if [path] is a top-level destination. */
    fun parentFolder(path: String): String? = null

    /** Creates folder [name] inside [parentPath]. Returns the new folder's path, or null. */
    fun createFolder(context: Context, parentPath: String, name: String): String? = null

    /** Opens the file at [path] for reading. The caller closes the stream. */
    fun openInput(context: Context, path: String): java.io.InputStream =
        throw UnsupportedOperationException()

    /** Modification time of [path] in epoch milliseconds, or 0 if unknown. */
    fun lastModified(context: Context, path: String): Long = 0L

    /** Size of the file at [path] in bytes, asked from the source itself; -1 if unknown. */
    fun fileSize(context: Context, path: String): Long = -1L

    /** Sets the modification date of [path] (what the gallery sorts by). Returns true on success. */
    fun setLastModified(context: Context, path: String, millis: Long): Boolean = false

    /** Size of the file at [path] if known without network access (e.g. from a listing); else -1. */
    fun cachedFileSize(path: String): Long = -1L

    /** Length of the video at [path] in milliseconds, or 0 if it cannot be determined. */
    fun videoDurationMs(context: Context, path: String): Long = 0L

    /**
     * Creates a new file in [folderPath] from [input]. A name that is already taken is made
     * unique ("name (1).jpg"). The new file gets [lastModified] as modification time (if > 0).
     * Returns the new file's path, or null on failure.
     */
    fun writeNew(
        context: Context,
        folderPath: String,
        desiredName: String,
        input: java.io.InputStream,
        lastModified: Long
    ): String? = null

    /** Deletes the file at [path]. Returns true once it is gone. */
    fun deleteFile(context: Context, path: String): Boolean = false

    /**
     * Why the last write, rename or delete of the calling thread failed, e.g. the server's
     * answer "STATUS_ACCESS_DENIED"; null if unknown. Reading it clears it.
     */
    fun lastError(): String? = null

    /** True if files at [path] can be moved to a trash that belongs to the source instead of being erased. */
    fun supportsTrash(path: String): Boolean = false

    /** Moves the file at [path] into the source's trash. Returns true on success. */
    fun moveToTrash(context: Context, path: String): Boolean = false

    /** Trash bins this source offers (e.g. one per network share), shown next to the phone's trash bin. */
    fun trashLocations(context: Context): List<TrashLocation> = emptyList()

    /**
     * Replaces the content of the existing file at [path] with [input] (e.g. an edited picture).
     * The old content stays untouched if this fails. Returns true on success.
     */
    fun replaceFile(
        context: Context,
        path: String,
        input: java.io.InputStream,
        lastModified: Long
    ): Boolean = false

    /**
     * Moves [path] into [destFolderPath] without transferring data (e.g. a rename on the same
     * share). Returns the new path, or null if that is not possible and the caller should
     * copy and delete instead.
     */
    fun moveWithin(
        context: Context,
        path: String,
        destFolderPath: String,
        desiredName: String
    ): String? = null

    /**
     * Lets this source offer its own actions for a home-screen entry (e.g. choosing a cover
     * image). Returns true if it showed something; false means the default (hide folder) applies.
     * [onHide] performs the default hide action, [onChanged] asks the home screen to refresh.
     */
    fun showHomeEntryOptions(
        activity: androidx.appcompat.app.AppCompatActivity,
        folder: MediaFolder,
        onHide: () -> Unit,
        onChanged: () -> Unit
    ): Boolean = false

    // ---- Whole folders (copy / move / delete) ----

    /** True if the folder at [path] can be copied, moved and deleted as a whole (not a share itself). */
    fun supportsFolderOperations(path: String): Boolean = false

    /** Files and folders directly inside [folderPath], without hidden ones. Blocking; throws if unreadable. */
    fun listFolder(context: Context, folderPath: String): List<FolderChild> = emptyList()

    /**
     * Moves the folder [path] with everything in it into [destFolderPath] as [name] without
     * transferring data (a rename on the same share). Returns the new path, or null if that is
     * not possible and the caller should move the files one by one. Blocking.
     */
    fun moveFolderWithin(
        context: Context,
        path: String,
        destFolderPath: String,
        name: String
    ): String? = null

    /**
     * Removes the folder [path] after its files were moved or deleted, but only if nothing is
     * left in it except empty folders and what the NAS keeps there on its own (thumbnails it
     * made, Thumbs.db, ...). Returns true once the folder is gone. Blocking.
     */
    fun removeEmptiedFolder(context: Context, path: String): Boolean = false

    // ---- Folder screen (sub folders, extra menu entries) ----

    /**
     * Sub folders of [folderPath] as cards for the top of the folder screen. Uses what is known
     * already (cover, count); [refreshSubFolderCard] brings a card up to date. Blocking.
     */
    fun subFolderCards(context: Context, folderPath: String): List<MediaFolder> = emptyList()

    /** [card] with fresh details (cover, count), or null if nothing could be read. Blocking. */
    fun refreshSubFolderCard(context: Context, card: MediaFolder): MediaFolder? = null

    /** Extra entries for the menu of the folder screen showing [folderPath] (e.g. the share's trash). */
    fun folderMenuActions(context: Context, folderPath: String): List<FolderAction> = emptyList()

    // ---- Settings export ----

    /** Name of this source's part in the settings file, or null if it has nothing to export. */
    val settingsExportKey: String?
        get() = null

    /** This source's settings for the settings file (never passwords), or null. */
    fun exportSettings(context: Context): org.json.JSONObject? = null

    /** Takes over what [exportSettings] wrote. Returns how many entries were added. */
    fun importSettings(context: Context, data: org.json.JSONObject): Int = 0

    /** Release any held resources (connections, caches) on app shutdown. */
    fun shutdown()
}

/** An entry for the folder screen's menu, offered by a [MediaSource]. */
class FolderAction(
    val label: String,
    val run: (androidx.appcompat.app.AppCompatActivity) -> Unit
)

/**
 * Set on a [MediaSource.browseIntent] opened from a sub folder card of the folder screen: going
 * back from that folder returns to the folder screen.
 */
const val EXTRA_OPENED_FROM_FOLDER_SCREEN = "org.iurl.litegallery.extra.OPENED_FROM_FOLDER_SCREEN"

/** A trash bin of a [MediaSource]: [label] is shown in the list, [open] opens it. */
class TrashLocation(
    val label: String,
    val open: (androidx.appcompat.app.AppCompatActivity) -> Unit
)

/** A folder offered by [MediaSource.destinationRoots] / [MediaSource.listSubFolders]. */
data class FolderEntry(val name: String, val path: String)

/** An entry of [MediaSource.listFolder]: a file (with its size) or a folder. */
data class FolderChild(val name: String, val path: String, val isDirectory: Boolean, val size: Long)

/** Result of a [MediaSource.rename] attempt. */
sealed class RenameOutcome {
    data class Success(val newPath: String) : RenameOutcome()
    object AlreadyExists : RenameOutcome()
    object Failed : RenameOutcome()
}
