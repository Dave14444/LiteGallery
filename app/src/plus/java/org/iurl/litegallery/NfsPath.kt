package org.iurl.litegallery

/**
 * Helper for parsing and constructing NFS paths.
 * Format: nfs://host/exported/path/inner/path/file.ext
 *
 * The export path and the path inside the export are joined in the URL (the same
 * convention libnfs uses). Because export paths themselves contain slashes, the
 * boundary cannot be recovered from the URL alone — [matchExport] resolves it
 * against the export paths of the saved server configs for the host.
 */
data class NfsPath(
    val host: String,
    val fullPath: String // "/export/inner/file.ext", or "" for a bare host
) {
    /** Full nfs:// URL */
    val fullUrl: String
        get() = "nfs://$host$fullPath"

    /** File name extracted from the path */
    val fileName: String
        get() = fullPath.substringAfterLast('/')

    /** File name without extension */
    val fileNameWithoutExtension: String
        get() = fileName.substringBeforeLast('.', fileName)

    /** File extension */
    val fileExtension: String
        get() = fileName.substringAfterLast('.', "")

    /** Full parent folder path as nfs:// URL */
    val parentFolderPath: String
        get() = "nfs://$host" + fullPath.substringBeforeLast('/', "")

    /**
     * The path inside [export] (starting with "/", "/" for the export root itself),
     * or null when this path is not inside that export.
     */
    fun innerPath(export: String): String? = when {
        fullPath == export -> "/"
        fullPath.startsWith("$export/") -> fullPath.removePrefix(export)
        else -> null
    }

    companion object {
        /**
         * Parse an nfs:// URL string. Returns null if the format is invalid.
         */
        fun parse(nfsUrl: String): NfsPath? {
            if (!isNfs(nfsUrl)) return null
            val rest = nfsUrl.removePrefix("nfs://").trimEnd('/')
            if (rest.isBlank()) return null
            val slash = rest.indexOf('/')
            if (slash == 0) return null
            return if (slash < 0) {
                NfsPath(rest, "")
            } else {
                NfsPath(rest.substring(0, slash), rest.substring(slash))
            }
        }

        /** Build a full nfs:// URL. [fullPath] must start with "/" or be empty. */
        fun toUrl(host: String, fullPath: String): String = "nfs://$host$fullPath"

        /** Check if a path string is an NFS path. */
        fun isNfs(path: String): Boolean = path.startsWith("nfs://")

        /**
         * From [exports], pick the longest one that [fullPath] lives inside
         * (segment-aware, so "/volume1/photo" does not match "/volume1/photos2/x").
         */
        fun matchExport(exports: List<String>, fullPath: String): String? =
            exports
                .filter { it.isNotBlank() && (fullPath == it || fullPath.startsWith("$it/")) }
                .maxByOrNull { it.length }
    }
}
