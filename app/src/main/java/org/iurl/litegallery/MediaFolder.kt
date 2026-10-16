package org.iurl.litegallery

import java.io.File

data class MediaFolder(
    val name: String,
    val path: String,
    val itemCount: Int,
    val thumbnail: String? = null,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val latestDateModifiedMs: Long = 0L,
    /** A plain sub folder (shown with the folder icon), not a source entry like a network share. */
    val isSubFolder: Boolean = false
) {
    fun getFile(): File = File(path)
}
