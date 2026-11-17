package org.iurl.litegallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Manages persistent storage of network server configurations using SharedPreferences.
 */
object NetworkConfigStore {

    // Legacy name kept on purpose so existing SMB bookmarks survive the rename.
    private const val PREFS_NAME = "smb_servers"
    private const val KEY_SERVERS = "servers_json"

    // Passwords are saved encrypted under KEY_PASSWORD; KEY_LEGACY_PASSWORD is the old plain text.
    private const val KEY_PASSWORD = "passwordEnc"
    private const val KEY_LEGACY_PASSWORD = "password"

    // Decrypting needs the Keystore, so the parsed list is kept until the saved text changes.
    private var cachedRaw: String? = null
    private var cachedServers: List<NetworkServerConfig> = emptyList()

    @Synchronized
    fun getAllServers(context: Context): List<NetworkServerConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        if (json == cachedRaw) return cachedServers

        return try {
            val array = JSONArray(json)
            var hasPlainPassword = false
            val servers = (0 until array.length()).mapNotNull { i ->
                val entry = array.getJSONObject(i)
                if (entry.optString(KEY_LEGACY_PASSWORD, "").isNotEmpty()) hasPlainPassword = true
                parseServer(entry)
            }
            cachedRaw = json
            cachedServers = servers
            // Versions before the encryption saved the password as plain text: encrypt it now.
            if (hasPlainPassword) saveServers(context, servers)
            servers
        } catch (e: Exception) {
            android.util.Log.e("NetworkConfigStore", "Failed to parse servers", e)
            emptyList()
        }
    }

    /** The saved server for [host] that uses [protocol], or null. */
    fun getServerByHost(context: Context, host: String, protocol: String): NetworkServerConfig? {
        return getAllServers(context).find {
            it.protocol == protocol && it.host.equals(host, ignoreCase = true)
        }
    }

    /**
     * The saved SMB connection to use for [share] on [host]. Several connections to one NAS may
     * log in as different users (e.g. one per person, and a guest connection to "Public"), so
     * the connection made for that share decides. Without one, any connection to the host.
     */
    fun getSmbServerForShare(context: Context, host: String, share: String): NetworkServerConfig? {
        val onHost = getAllServers(context).filter {
            it.protocol == NetworkServerConfig.PROTOCOL_SMB && it.host.equals(host, ignoreCase = true)
        }
        return onHost.firstOrNull { it.share.equals(share, ignoreCase = true) } ?: onHost.firstOrNull()
    }

    fun getServerById(context: Context, id: String): NetworkServerConfig? {
        return getAllServers(context).find { it.id == id }
    }

    fun addServer(context: Context, config: NetworkServerConfig): NetworkServerConfig {
        val configWithId = if (config.id.isBlank()) {
            config.copy(id = UUID.randomUUID().toString())
        } else {
            config
        }
        val servers = getAllServers(context).toMutableList()
        servers.add(configWithId)
        saveServers(context, servers)
        return configWithId
    }

    fun updateServer(context: Context, config: NetworkServerConfig) {
        val servers = getAllServers(context).toMutableList()
        val index = servers.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            servers[index] = config
            saveServers(context, servers)
        }
    }

    /** Whether passwords can be saved (encrypted) on this device; they are never saved as plain text. */
    fun canSavePasswords(): Boolean = PasswordCipher.encrypt("check") != null

    fun deleteServer(context: Context, id: String) {
        val servers = getAllServers(context).toMutableList()
        servers.removeAll { it.id == id }
        saveServers(context, servers)
    }

    // ---------------------------------------------------------------- settings export

    /** The saved servers for the settings file: everything except the passwords. */
    fun exportWithoutPasswords(context: Context): JSONArray {
        val array = JSONArray()
        getAllServers(context).forEach { config ->
            array.put(serializeServer(config, includePassword = false))
        }
        return array
    }

    /**
     * Adds the servers of a settings file that are not saved here yet (same protocol and address).
     * They come without password; servers already saved keep theirs. Returns, for each added
     * server, its id in the file and its id here.
     */
    @Synchronized
    fun importWithoutPasswords(context: Context, array: JSONArray): Map<String, String> {
        val servers = getAllServers(context).toMutableList()
        val added = LinkedHashMap<String, String>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val fileId = entry.optString("id", "")
            val imported = parseServer(entry)?.copy(password = "") ?: continue
            if (imported.host.isBlank() || servers.any { sameAddress(it, imported) }) continue

            val id = if (fileId.isNotBlank() && servers.none { it.id == fileId }) {
                fileId
            } else {
                UUID.randomUUID().toString()
            }
            servers.add(imported.copy(id = id))
            added[fileId] = id
        }
        if (added.isNotEmpty()) saveServers(context, servers)
        return added
    }

    private fun sameAddress(a: NetworkServerConfig, b: NetworkServerConfig): Boolean =
        a.protocol == b.protocol &&
            a.host.equals(b.host, ignoreCase = true) &&
            a.share.equals(b.share, ignoreCase = true) &&
            a.path.trim('/').equals(b.path.trim('/'), ignoreCase = true)

    @Synchronized
    private fun saveServers(context: Context, servers: List<NetworkServerConfig>) {
        val array = JSONArray()
        servers.forEach { config ->
            array.put(serializeServer(config))
        }
        val json = array.toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVERS, json)
            .apply()
        cachedRaw = json
        cachedServers = servers
    }

    private fun serializeServer(config: NetworkServerConfig, includePassword: Boolean = true): JSONObject {
        return JSONObject().apply {
            put("id", config.id)
            put("displayName", config.displayName)
            put("host", config.host)
            put("protocol", config.protocol)
            put("share", config.share)
            put("path", config.path)
            put("port", config.port)
            put("username", config.username)
            if (includePassword) {
                // Never as plain text: if the Keystore cannot encrypt it, the password is not
                // saved at all and has to be entered again (see canSavePasswords).
                PasswordCipher.encrypt(config.password)?.let { put(KEY_PASSWORD, it) }
            }
            put("isGuest", config.isGuest)
        }
    }

    private fun parseServer(json: JSONObject): NetworkServerConfig? {
        // Entries without a protocol field are SMB. Other protocols (NFS of earlier versions)
        // are no longer supported: such entries are left out (and gone with the next save).
        val protocol = json.optString("protocol", NetworkServerConfig.PROTOCOL_SMB)
        if (protocol != NetworkServerConfig.PROTOCOL_SMB) return null
        return try {
            NetworkServerConfig(
                id = json.optString("id", UUID.randomUUID().toString()),
                displayName = json.optString("displayName", ""),
                host = json.optString("host", ""),
                protocol = protocol,
                share = json.optString("share", ""),
                path = json.optString("path", ""),
                port = json.optInt("port", 445),
                username = json.optString("username", ""),
                password = readPassword(json),
                isGuest = json.optBoolean("isGuest", false)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** The password of an entry: decrypted, or the plain text saved by older versions. */
    private fun readPassword(json: JSONObject): String {
        val encrypted = json.optString(KEY_PASSWORD, "")
        if (encrypted.isNotEmpty()) {
            // Null: the key is gone (e.g. restored on another phone). The password must be re-entered.
            return PasswordCipher.decrypt(encrypted) ?: ""
        }
        return json.optString(KEY_LEGACY_PASSWORD, "")
    }
}
