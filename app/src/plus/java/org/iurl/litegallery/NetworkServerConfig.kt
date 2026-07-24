package org.iurl.litegallery

/**
 * Data model for a saved network server connection (SMB or NFS).
 */
data class NetworkServerConfig(
    val id: String,           // Unique ID (UUID)
    val displayName: String,  // User-friendly display name
    val host: String,         // IP or hostname
    val protocol: String = PROTOCOL_SMB,
    val share: String = "",   // SMB: the share to connect to
    val path: String = "",    // SMB: the path within the share
    val port: Int = 445,      // SMB port; ignored for NFS (the portmapper decides ports)
    val username: String = "",
    val password: String = "",
    val isGuest: Boolean = false,
    val export: String = "",  // NFS: exported path, e.g. "/volume1/photo"
    val uid: Int = 0,         // NFS: AUTH_UNIX uid
    val gid: Int = 0          // NFS: AUTH_UNIX gid
) {
    val isAuthenticated: Boolean
        get() = !isGuest && username.isNotBlank()

    val isNfs: Boolean
        get() = protocol == PROTOCOL_NFS

    /** Root browse URL for this server entry. */
    fun rootUrl(): String = if (isNfs) {
        "nfs://$host$export"
    } else if (share.isNotBlank()) {
        "smb://$host/$share" + (if (path.isNotBlank()) "/$path" else "")
    } else {
        "smb://$host"
    }

    companion object {
        const val PROTOCOL_SMB = "smb"
        const val PROTOCOL_NFS = "nfs"
    }
}
