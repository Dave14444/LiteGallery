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

    fun getAllServers(context: Context): List<NetworkServerConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                parseServer(array.getJSONObject(i))
            }
        } catch (e: Exception) {
            android.util.Log.e("NetworkConfigStore", "Failed to parse servers", e)
            emptyList()
        }
    }

    fun getServerByHost(context: Context, host: String): NetworkServerConfig? {
        return getAllServers(context).find { it.host.equals(host, ignoreCase = true) }
    }

    /**
     * The saved server for [host] that uses [protocol], or null. Filtering by protocol
     * matters when a host has both an SMB and an NFS entry: an SMB caller must not
     * receive the NFS config (which carries no share/credentials) or vice versa.
     */
    fun getServerByHost(context: Context, host: String, protocol: String): NetworkServerConfig? {
        return getAllServers(context).find {
            it.protocol == protocol && it.host.equals(host, ignoreCase = true)
        }
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

    fun deleteServer(context: Context, id: String) {
        val servers = getAllServers(context).toMutableList()
        servers.removeAll { it.id == id }
        saveServers(context, servers)
    }

    private fun saveServers(context: Context, servers: List<NetworkServerConfig>) {
        val array = JSONArray()
        servers.forEach { config ->
            array.put(serializeServer(config))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVERS, array.toString())
            .apply()
    }

    private fun serializeServer(config: NetworkServerConfig): JSONObject {
        return JSONObject().apply {
            put("id", config.id)
            put("displayName", config.displayName)
            put("host", config.host)
            put("protocol", config.protocol)
            put("share", config.share)
            put("path", config.path)
            put("port", config.port)
            put("username", config.username)
            put("password", config.password)
            put("isGuest", config.isGuest)
            put("export", config.export)
            put("uid", config.uid)
            put("gid", config.gid)
        }
    }

    private fun parseServer(json: JSONObject): NetworkServerConfig? {
        return try {
            NetworkServerConfig(
                id = json.optString("id", UUID.randomUUID().toString()),
                displayName = json.optString("displayName", ""),
                host = json.optString("host", ""),
                // Entries saved before the NFS feature have no protocol field: they are SMB.
                protocol = json.optString("protocol", NetworkServerConfig.PROTOCOL_SMB),
                share = json.optString("share", ""),
                path = json.optString("path", ""),
                port = json.optInt("port", 445),
                username = json.optString("username", ""),
                password = json.optString("password", ""),
                isGuest = json.optBoolean("isGuest", false),
                export = json.optString("export", ""),
                uid = json.optInt("uid", 0),
                gid = json.optInt("gid", 0)
            )
        } catch (e: Exception) {
            null
        }
    }
}
