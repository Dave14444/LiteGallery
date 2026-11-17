package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ServerIdentityTest {

    private lateinit var context: Context
    private val nas = UUID.fromString("73646d79-636c-6f75-6400-000000000000")
    private val stranger = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServerIdentity.forget(context, "192.168.178.20", 445)
    }

    @Test
    fun aNasNotKnownYet_isAccepted_andRememberedAfterTheLogin() {
        ServerIdentity.check(context, "192.168.178.20", 445, nas)
        ServerIdentity.remember(context, "192.168.178.20", 445, nas)
        ServerIdentity.check(context, "192.168.178.20", 445, nas)
    }

    @Test
    fun anotherDeviceAtTheSameAddress_getsNoLogin() {
        ServerIdentity.remember(context, "192.168.178.20", 445, nas)
        try {
            ServerIdentity.check(context, "192.168.178.20", 445, stranger)
            fail("Another device must be refused")
        } catch (e: ServerIdentity.ChangedException) {
            assertEquals("192.168.178.20", e.host)
            assertTrue(e.message.orEmpty().contains("192.168.178.20"))
        }
    }

    @Test
    fun onlyTheFirstLogin_counts() {
        ServerIdentity.remember(context, "192.168.178.20", 445, nas)
        ServerIdentity.remember(context, "192.168.178.20", 445, stranger)
        ServerIdentity.check(context, "192.168.178.20", 445, nas)
    }

    @Test
    fun afterForgetting_aNewNasIsAccepted() {
        ServerIdentity.remember(context, "192.168.178.20", 445, nas)
        ServerIdentity.forget(context, "192.168.178.20", 445)
        ServerIdentity.check(context, "192.168.178.20", 445, stranger)
    }

    @Test
    fun theAddress_isComparedWithoutCase() {
        assertEquals(ServerIdentity.key("MyCloud.local", 445), ServerIdentity.key(" mycloud.LOCAL", 445))
    }
}
