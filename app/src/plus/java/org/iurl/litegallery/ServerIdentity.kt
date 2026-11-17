package org.iurl.litegallery

import android.content.Context
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Recognizes the NAS again. Before logging in, the NAS tells its server id (Samba derives it
 * from the NAS name, so it stays the same across restarts). It is remembered with the first
 * successful login; afterwards a device that answers at the same address with another id gets
 * no login at all, for example a foreign device in another network that happens to use the
 * same address as the NAS at home.
 *
 * This is no proof (the id is not secret), but it stops the tools that collect logins in
 * foreign networks. The real protection outside the home network is the VPN (see NetworkAccess).
 */
object ServerIdentity {

    /** The device at [host] is not the NAS known there: no login was sent. */
    class ChangedException(val host: String, val port: Int, message: String) : IOException(message)

    private const val PREFS = "smb_server_identity"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun key(host: String, port: Int): String = "${host.trim().lowercase(Locale.ROOT)}:$port"

    /** Throws [ChangedException] if another id than the remembered one answered at [host]. */
    @Throws(ChangedException::class)
    fun check(context: Context, host: String, port: Int, serverId: UUID?) {
        val known = prefs(context).getString(key(host, port), null) ?: return
        if (serverId == null || serverId.toString() != known) {
            throw ChangedException(host, port, context.getString(R.string.nas_identity_changed, host))
        }
    }

    /** Remembers the id of the NAS at [host] after a successful login (only the first one counts). */
    fun remember(context: Context, host: String, port: Int, serverId: UUID?) {
        if (serverId == null) return
        val key = key(host, port)
        val store = prefs(context)
        if (store.contains(key)) return
        store.edit().putString(key, serverId.toString()).apply()
    }

    /** The NAS at [host] was replaced (or renamed): the next successful login is remembered anew. */
    fun forget(context: Context, host: String, port: Int) {
        prefs(context).edit().remove(key(host, port)).apply()
    }
}
