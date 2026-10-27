package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NetworkConfigStoreTest {

    private lateinit var context: Context

    private val originalKeyProvider = PasswordCipher.keyProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // The Android Keystore does not exist in unit tests: use a fixed key instead.
        PasswordCipher.keyProvider = { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        clearStore()
    }

    @After
    fun tearDown() {
        clearStore()
        PasswordCipher.keyProvider = originalKeyProvider
    }

    private fun storedJson(): String =
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
            .getString("servers_json", "") ?: ""

    @Test
    fun password_isNeverSavedAsPlainText() {
        val saved = NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "", displayName = "NAS", host = "nas.local", share = "Photos",
                username = "arthur", password = "sehr-geheim-123"
            )
        )

        assertFalse(storedJson().contains("sehr-geheim-123"))
        assertEquals("sehr-geheim-123", NetworkConfigStore.getServerById(context, saved.id)?.password)
    }

    @Test
    fun password_survivesAFreshReadFromStorage() {
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(id = "a", displayName = "NAS", host = "nas.local", password = "pässwörd €")
        )
        // Same content, but a different text (a space at the end): the in-memory copy is not used.
        val raw = storedJson() + " "
        clearStore()
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
            .edit().putString("servers_json", raw).commit()

        assertEquals("pässwörd €", NetworkConfigStore.getServerById(context, "a")?.password)
    }

    @Test
    fun plainTextPasswordOfOlderVersions_isEncryptedOnFirstRead() {
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "servers_json",
                """[{"id":"old","displayName":"NAS","host":"nas.local","share":"Photos",""" +
                    """"path":"","port":445,"username":"u","password":"alt-passwort","isGuest":false}]"""
            )
            .commit()

        val servers = NetworkConfigStore.getAllServers(context)

        assertEquals("alt-passwort", servers.single().password)
        assertFalse(storedJson().contains("alt-passwort"))
        assertEquals("alt-passwort", NetworkConfigStore.getServerById(context, "old")?.password)
    }

    @Test
    fun passwordThatCannotBeDecrypted_comesBackEmptyInsteadOfBreakingTheList() {
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(id = "a", displayName = "NAS", host = "nas.local", password = "pw")
        )
        val raw = storedJson() + " "
        // A different key, like after a restore on another phone.
        PasswordCipher.keyProvider = { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
        clearStore()
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
            .edit().putString("servers_json", raw).commit()

        val server = NetworkConfigStore.getServerById(context, "a")

        assertEquals("nas.local", server?.host)
        assertEquals("", server?.password)
    }

    @Test
    fun addServer_generatesIdAndPersistsFields() {
        val saved = NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "",
                displayName = "NAS",
                host = "nas.local",
                share = "Photos",
                path = "Trips",
                port = 1445,
                username = "arthur",
                password = "secret"
            )
        )

        assertNotEquals("", saved.id)
        assertTrue(saved.isAuthenticated)
        assertEquals(saved, NetworkConfigStore.getServerById(context, saved.id))
    }

    @Test
    fun getServerByHost_matchesIgnoringCase() {
        val saved = NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(id = "one", displayName = "NAS", host = "NAS.LOCAL", isGuest = true)
        )

        assertEquals(
            saved,
            NetworkConfigStore.getServerByHost(context, "nas.local", NetworkServerConfig.PROTOCOL_SMB)
        )
    }

    @Test
    fun getServerByHost_withProtocol_returnsConfigOfThatProtocolIgnoringCase() {
        // NFS entry saved first, SMB entry second, on the same host. The SMB-filtered
        // lookup must return the SMB config, not the NFS one that was stored earlier.
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "nfs", displayName = "NFS", host = "nas.local",
                protocol = NetworkServerConfig.PROTOCOL_NFS, export = "/volume1/photo"
            )
        )
        val smb = NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "smb", displayName = "SMB", host = "nas.local",
                share = "Photos", username = "bob", password = "pw"
            )
        )

        val resolved = NetworkConfigStore.getServerByHost(
            context, "NAS.LOCAL", NetworkServerConfig.PROTOCOL_SMB
        )

        assertEquals(smb, resolved)
        assertEquals(NetworkServerConfig.PROTOCOL_SMB, resolved?.protocol)
    }

    @Test
    fun getServerByHost_withProtocol_returnsNullWhenNoConfigOfThatProtocol() {
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "nfs", displayName = "NFS", host = "nas.local",
                protocol = NetworkServerConfig.PROTOCOL_NFS, export = "/volume1/photo"
            )
        )

        assertNull(
            NetworkConfigStore.getServerByHost(
                context, "nas.local", NetworkServerConfig.PROTOCOL_SMB
            )
        )
    }

    @Test
    fun updateServer_replacesMatchingIdOnly() {
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(id = "one", displayName = "Old", host = "one.local")
        )
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(id = "two", displayName = "Other", host = "two.local")
        )

        NetworkConfigStore.updateServer(
            context,
            NetworkServerConfig(id = "one", displayName = "New", host = "one.local", share = "Media")
        )

        assertEquals("New", NetworkConfigStore.getServerById(context, "one")?.displayName)
        assertEquals("Media", NetworkConfigStore.getServerById(context, "one")?.share)
        assertEquals("Other", NetworkConfigStore.getServerById(context, "two")?.displayName)
    }

    @Test
    fun deleteServer_removesMatchingIdOnly() {
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(id = "one", displayName = "One", host = "one.local")
        )
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(id = "two", displayName = "Two", host = "two.local")
        )

        NetworkConfigStore.deleteServer(context, "one")

        assertNull(NetworkConfigStore.getServerById(context, "one"))
        assertNotNull(NetworkConfigStore.getServerById(context, "two"))
    }

    @Test
    fun getAllServers_returnsEmptyListForMalformedJson() {
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
            .edit()
            .putString("servers_json", "{not json")
            .commit()

        assertTrue(NetworkConfigStore.getAllServers(context).isEmpty())
    }

    @Test
    fun getAllServers_parsesLegacyEntriesWithoutProtocolAsSmb() {
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "servers_json",
                """[{"id":"legacy","displayName":"NAS","host":"nas.local","share":"Photos",""" +
                    """"path":"","port":445,"username":"u","password":"p","isGuest":false}]"""
            )
            .commit()

        val servers = NetworkConfigStore.getAllServers(context)

        assertEquals(1, servers.size)
        assertEquals(NetworkServerConfig.PROTOCOL_SMB, servers[0].protocol)
        assertEquals("", servers[0].export)
        assertEquals(0, servers[0].uid)
        assertEquals(0, servers[0].gid)
    }

    @Test
    fun addServer_roundTripsNfsFields() {
        val saved = NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "",
                displayName = "NAS NFS",
                host = "nas.local",
                protocol = NetworkServerConfig.PROTOCOL_NFS,
                export = "/volume1/photo",
                uid = 1000,
                gid = 100
            )
        )

        val loaded = NetworkConfigStore.getServerById(context, saved.id)

        assertEquals(NetworkServerConfig.PROTOCOL_NFS, loaded?.protocol)
        assertEquals("/volume1/photo", loaded?.export)
        assertEquals(1000, loaded?.uid)
        assertEquals(100, loaded?.gid)
        assertEquals(true, loaded?.isNfs)
    }

    @Test
    fun rootUrl_buildsProtocolSpecificRoot() {
        assertEquals(
            "nfs://nas.local/volume1/photo",
            NetworkServerConfig(
                id = "a", displayName = "", host = "nas.local",
                protocol = NetworkServerConfig.PROTOCOL_NFS, export = "/volume1/photo"
            ).rootUrl()
        )
        assertEquals(
            "smb://nas.local/Photos/Trips",
            NetworkServerConfig(
                id = "b", displayName = "", host = "nas.local", share = "Photos", path = "Trips"
            ).rootUrl()
        )
        assertEquals(
            "smb://nas.local",
            NetworkServerConfig(id = "c", displayName = "", host = "nas.local").rootUrl()
        )
    }

    private fun clearStore() {
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
