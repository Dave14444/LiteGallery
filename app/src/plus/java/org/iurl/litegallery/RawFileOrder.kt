package org.iurl.litegallery

/**
 * The order of RAW files for [sortOrder] (see GallerySortOrder), like the gallery sorts pictures:
 * by the file's time, name or size; equal ones by name in the same direction.
 */
internal fun rawFileOrder(sortOrder: String): Comparator<RemoteFileInfo> {
    val byName = compareBy<RemoteFileInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    return when (sortOrder) {
        "date_asc" -> compareBy<RemoteFileInfo> { it.lastModified }.then(byName)
        "name_asc" -> byName
        "name_desc" -> byName.reversed()
        "size_desc" -> compareByDescending<RemoteFileInfo> { it.size }.then(byName.reversed())
        "size_asc" -> compareBy<RemoteFileInfo> { it.size }.then(byName)
        else -> compareByDescending<RemoteFileInfo> { it.lastModified }.then(byName.reversed())
    }
}
