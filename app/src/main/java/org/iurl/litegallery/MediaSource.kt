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

    fun homeEntries(context: Context): List<MediaFolder> =
    listOfNotNull(homeEntry(context))

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

    /** Release any held resources (connections, caches) on app shutdown. */
    fun shutdown()
}

/** A folder offered by [MediaSource.destinationRoots] / [MediaSource.listSubFolders]. */
data class FolderEntry(val name: String, val path: String)

/** Result of a [MediaSource.rename] attempt. */
sealed class RenameOutcome {
    data class Success(val newPath: String) : RenameOutcome()
    object AlreadyExists : RenameOutcome()
    object Failed : RenameOutcome()
}
