package org.iurl.litegallery

/**
 * Data model for a saved network server connection (SMB).
 */
data class NetworkServerConfig(
    val id: String,           // Unique ID (UUID)
    val displayName: String,  // User-friendly display name
    val host: String,         // IP or hostname
    val protocol: String = PROTOCOL_SMB,
    val share: String = "",   // the share to connect to
    val path: String = "",    // the path within the share
    val port: Int = 445,
    val username: String = "",
    val password: String = "",
    val isGuest: Boolean = false
) {
    val isAuthenticated: Boolean
        get() = !isGuest && username.isNotBlank()

    /** Root browse URL for this server entry. */
    fun rootUrl(): String = if (share.isNotBlank()) {
        "smb://$host/$share" + (if (path.isNotBlank()) "/$path" else "")
    } else {
        "smb://$host"
    }

    companion object {
        const val PROTOCOL_SMB = "smb"
    }
}
