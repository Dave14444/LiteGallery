package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.iurl.litegallery.NetworkAccess.Status
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NetworkAccessTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NetworkAccess.saveRule(context, requireVpn = false, homeWifiName = "")
    }

    @After
    fun tearDown() {
        NetworkAccess.saveRule(context, requireVpn = false, homeWifiName = "")
    }

    private fun decide(
        vpn: Boolean = false,
        wifi: Boolean = true,
        name: String? = "J.A.R.V.I.S.",
        permission: Boolean = true,
        location: Boolean = true
    ) = NetworkAccess.decide(vpn, wifi, name, "J.A.R.V.I.S.", permission, location)

    @Test
    fun inTheHomeWifi_theNasIsUsedDirectly() {
        assertEquals(Status.ALLOWED, decide())
    }

    @Test
    fun withTheVpnOn_anyNetworkIsFine() {
        assertEquals(Status.ALLOWED, decide(vpn = true, wifi = false, name = null))
        assertEquals(Status.ALLOWED, decide(vpn = true, name = "Hotel-WLAN"))
    }

    @Test
    fun onMobileData_orInAnotherWifi_theVpnIsNeeded() {
        assertEquals(Status.NEEDS_VPN_NO_WIFI, decide(wifi = false, name = null))
        assertEquals(Status.NEEDS_VPN_OTHER_WIFI, decide(name = "Hotel-WLAN"))
        // Wi-Fi names are exact: another spelling is another Wi-Fi.
        assertEquals(Status.NEEDS_VPN_OTHER_WIFI, decide(name = "j.a.r.v.i.s."))
    }

    @Test
    fun anUnreadableWifiName_isNeverTakenForTheHomeWifi() {
        assertEquals(Status.UNKNOWN_NO_PERMISSION, decide(name = null, permission = false))
        assertEquals(Status.UNKNOWN_LOCATION_OFF, decide(name = null, location = false))
        assertEquals(Status.UNKNOWN, decide(name = null))
    }

    @Test
    fun wifiNames_comeWithoutTheQuotesAndroidAdds() {
        assertEquals("J.A.R.V.I.S.", NetworkAccess.normalizeWifiName("\"J.A.R.V.I.S.\""))
        assertEquals("4a41525649", NetworkAccess.normalizeWifiName("4a41525649"))
        assertNull(NetworkAccess.normalizeWifiName("<unknown ssid>"))
        assertNull(NetworkAccess.normalizeWifiName(""))
        assertNull(NetworkAccess.normalizeWifiName(null))
    }

    @Test
    fun theRule_needsTheSwitchAndTheHomeWifi() {
        assertFalse(NetworkAccess.isRuleActive(context))
        assertEquals(Status.ALLOWED, NetworkAccess.status(context))

        NetworkAccess.saveRule(context, requireVpn = true, homeWifiName = "")
        assertFalse(NetworkAccess.isRuleActive(context))

        NetworkAccess.saveRule(context, requireVpn = true, homeWifiName = "J.A.R.V.I.S.")
        assertTrue(NetworkAccess.isRuleActive(context))
        assertEquals("J.A.R.V.I.S.", NetworkAccess.homeWifiName(context))
    }
}
