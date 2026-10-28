package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class NetworkServerExportTest {

    private lateinit var context: Context
    private val originalKeyProvider = PasswordCipher.keyProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PasswordCipher.keyProvider = { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        clear()
    }

    @After
    fun tearDown() {
        clear()
        PasswordCipher.keyProvider = originalKeyProvider
    }

    private fun clear() {
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("smb_cover_images", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun nas(id: String = "nas-1", password: String = "geheim-4711") = NetworkServerConfig(
        id = id,
        displayName = "NAS",
        host = "nas.local",
        share = "Photos",
        path = "",
        username = "dave",
        password = password
    )

    @Test
    fun export_containsTheServerButNoPassword() {
        NetworkConfigStore.addServer(context, nas())

        val text = NetworkMediaSource.exportSettings(context).toString()

        assertTrue(text.contains("nas.local"))
        assertTrue(text.contains("dave"))
        assertFalse(text.contains("geheim-4711"))
        assertFalse(text.contains("passwordEnc"))
        assertFalse(text.contains("\"password\""))
    }

    @Test
    fun import_addsMissingServersWithoutPassword() {
        NetworkConfigStore.addServer(context, nas())
        val exported = NetworkMediaSource.exportSettings(context)
        clear()

        val added = NetworkMediaSource.importSettings(context, exported)

        assertEquals(1, added)
        val server = NetworkConfigStore.getServerById(context, "nas-1")
        assertEquals("nas.local", server?.host)
        assertEquals("dave", server?.username)
        assertEquals("", server?.password)
    }

    @Test
    fun import_keepsServersThatAreAlreadyThere() {
        NetworkConfigStore.addServer(context, nas())
        val exported = NetworkMediaSource.exportSettings(context)

        val added = NetworkMediaSource.importSettings(context, exported)

        assertEquals(0, added)
        assertEquals(1, NetworkConfigStore.getAllServers(context).size)
        assertEquals("geheim-4711", NetworkConfigStore.getServerById(context, "nas-1")?.password)
    }

    @Test
    fun import_givesANewIdIfTheOldOneIsTakenByAnotherServer() {
        NetworkConfigStore.addServer(context, nas())
        val exported = NetworkMediaSource.exportSettings(context)
        clear()
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(id = "nas-1", displayName = "Other", host = "other.local", share = "Media")
        )

        val added = NetworkMediaSource.importSettings(context, exported)

        assertEquals(1, added)
        val servers = NetworkConfigStore.getAllServers(context)
        assertEquals(2, servers.size)
        assertEquals(2, servers.map { it.id }.toSet().size)
        assertEquals("other.local", NetworkConfigStore.getServerById(context, "nas-1")?.host)
    }

    @Test
    fun coverPicture_travelsWithTheServer() {
        NetworkConfigStore.addServer(context, nas())
        val picture = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(
            SmbCoverImages.importCover(
                context, "nas-1", android.util.Base64.encodeToString(picture, android.util.Base64.NO_WRAP)
            )
        )
        val exported = NetworkMediaSource.exportSettings(context)
        clear()
        assertNull(SmbCoverImages.coverPath(context, "nas-1"))

        NetworkMediaSource.importSettings(context, exported)

        val path = SmbCoverImages.coverPath(context, "nas-1")
        assertTrue(path != null && java.io.File(path).readBytes().contentEquals(picture))
    }
}
