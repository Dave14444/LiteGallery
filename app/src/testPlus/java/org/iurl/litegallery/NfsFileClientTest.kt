package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NfsFileClientTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearStore()
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "short", displayName = "vol", host = "nas.local",
                protocol = NetworkServerConfig.PROTOCOL_NFS, export = "/volume1"
            )
        )
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "long", displayName = "photo", host = "nas.local",
                protocol = NetworkServerConfig.PROTOCOL_NFS, export = "/volume1/photo",
                uid = 1000, gid = 100
            )
        )
    }

    @After
    fun tearDown() = clearStore()

    @Test
    fun resolveTarget_matchesLongestExportAndStripsInnerPath() {
        val target =
            NfsFileClient.resolveTarget(context, "nfs://nas.local/volume1/photo/Trips/a.jpg")

        assertEquals("/volume1/photo", target.export)
        assertEquals("/Trips/a.jpg", target.innerPath)
        assertEquals("long", target.config.id)
        assertEquals(1000, target.config.uid)
    }

    @Test
    fun resolveTarget_usesShorterExportWhenLongerDoesNotMatch() {
        val target =
            NfsFileClient.resolveTarget(context, "nfs://nas.local/volume1/music/b.mp4")

        assertEquals("/volume1", target.export)
        assertEquals("/music/b.mp4", target.innerPath)
    }

    @Test
    fun resolveTarget_throwsWhenHostHasNoNfsConfig() {
        assertThrows(NfsFileClient.NfsException::class.java) {
            NfsFileClient.resolveTarget(context, "nfs://unknown.host/volume1/a.jpg")
        }
    }

    @Test
    fun resolveTarget_throwsWhenNoExportMatchesPath() {
        assertThrows(NfsFileClient.NfsException::class.java) {
            NfsFileClient.resolveTarget(context, "nfs://nas.local/volume9/a.jpg")
        }
    }

    @Test
    fun resolveTarget_throwsForInvalidUrl() {
        assertThrows(NfsFileClient.NfsException::class.java) {
            NfsFileClient.resolveTarget(context, "smb://nas.local/share/a.jpg")
        }
    }

    private fun clearStore() {
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
