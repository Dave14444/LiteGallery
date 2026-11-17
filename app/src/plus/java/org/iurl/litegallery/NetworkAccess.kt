package org.iurl.litegallery

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * When the NAS may be contacted: in the home Wi-Fi directly, anywhere else only through the VPN.
 *
 * Outside the home network the NAS address (e.g. 192.168.178.20) may belong to any device of
 * that network. Logging in there would hand that device the login (in a form that can be tried
 * out offline). So without VPN, outside the home Wi-Fi, no connection is opened at all: every
 * new connection is checked here first (see SmbClient), and the network screen asks to switch on
 * the VPN.
 *
 * Android only tells the name of the Wi-Fi with the location permission, while the location is
 * switched on. Without it, the home Wi-Fi cannot be recognized and the VPN is asked for.
 */
object NetworkAccess {

    /** Whether a connection to the NAS may be opened now, and if not, why. */
    enum class Status {
        /** No rule set up, the VPN is on, or the phone is in the home Wi-Fi. */
        ALLOWED,
        /** Not in a Wi-Fi (mobile data): VPN needed. */
        NEEDS_VPN_NO_WIFI,
        /** In another Wi-Fi: VPN needed. */
        NEEDS_VPN_OTHER_WIFI,
        /** In a Wi-Fi whose name cannot be read: no location permission. */
        UNKNOWN_NO_PERMISSION,
        /** In a Wi-Fi whose name cannot be read: location switched off. */
        UNKNOWN_LOCATION_OFF,
        /** In a Wi-Fi whose name cannot be read (e.g. while the app is in the background). */
        UNKNOWN
    }

    /** A connection was not opened because of the rule above; [status] says why. */
    class NotAllowedException(val status: Status, message: String) : IOException(message)

    private const val PREFS = "network_access"
    private const val KEY_REQUIRE_VPN = "require_vpn_outside_home"
    private const val KEY_HOME_WIFI = "home_wifi_name"
    private const val KEY_ENCRYPT = "encrypt_smb"

    /** How long to wait for Android to tell the Wi-Fi name. */
    private const val WIFI_NAME_TIMEOUT_MS = 1_500L

    @Volatile
    private var appContext: Context? = null

    /** Wi-Fi connections recognized as the home Wi-Fi (also usable in the background), by network handle. */
    private val homeNetworks: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The app's context once [init] ran (null in unit tests that do not set it up). */
    fun context(): Context? = appContext

    // ---------------------------------------------------------------- settings

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun homeWifiName(context: Context): String = prefs(context).getString(KEY_HOME_WIFI, "").orEmpty()

    fun isVpnRequiredOutsideHome(context: Context): Boolean = prefs(context).getBoolean(KEY_REQUIRE_VPN, false)

    /** The rule applies once it is switched on and the home Wi-Fi is known. */
    fun isRuleActive(context: Context): Boolean =
        isVpnRequiredOutsideHome(context) && homeWifiName(context).isNotBlank()

    fun saveRule(context: Context, requireVpn: Boolean, homeWifiName: String) {
        prefs(context).edit()
            .putBoolean(KEY_REQUIRE_VPN, requireVpn)
            .putString(KEY_HOME_WIFI, homeWifiName)
            .apply()
        homeNetworks.clear()
    }

    /** Whether the traffic to the NAS is to be encrypted (SMB3). */
    fun encryptTraffic(context: Context): Boolean = prefs(context).getBoolean(KEY_ENCRYPT, false)

    fun setEncryptTraffic(context: Context, encrypt: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENCRYPT, encrypt).apply()
    }

    // ---------------------------------------------------------------- the check

    /** Throws [NotAllowedException] if no connection to the NAS may be opened now. Blocks briefly. */
    @Throws(NotAllowedException::class)
    fun requireAllowed() {
        val context = appContext ?: return
        val status = status(context)
        if (status != Status.ALLOWED) throw NotAllowedException(status, describe(context, status))
    }

    /** Whether a connection to the NAS may be opened now. Blocks up to a second or two: not on the main thread. */
    fun status(context: Context): Status {
        if (!isRuleActive(context)) return Status.ALLOWED
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return Status.ALLOWED
        val network = connectivity.activeNetwork ?: return Status.NEEDS_VPN_NO_WIFI
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return Status.NEEDS_VPN_NO_WIFI
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return Status.ALLOWED
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Status.NEEDS_VPN_NO_WIFI
        if (network.networkHandle in homeNetworks) return Status.ALLOWED

        val name = wifiName(context, network)
        val home = homeWifiName(context)
        val status = decide(
            vpn = false,
            wifi = true,
            wifiName = name,
            homeWifiName = home,
            hasPermission = hasLocationPermission(context),
            locationOn = isLocationOn(context)
        )
        // Recognized now (the app is in front): later checks of this connection, also from the
        // background (e.g. preparing previews), do not need the name any more.
        if (status == Status.ALLOWED) homeNetworks.add(network.networkHandle)
        return status
    }

    /** The decision itself, apart from Android (for tests). */
    internal fun decide(
        vpn: Boolean,
        wifi: Boolean,
        wifiName: String?,
        homeWifiName: String,
        hasPermission: Boolean,
        locationOn: Boolean
    ): Status = when {
        vpn -> Status.ALLOWED
        !wifi -> Status.NEEDS_VPN_NO_WIFI
        wifiName == null && !hasPermission -> Status.UNKNOWN_NO_PERMISSION
        wifiName == null && !locationOn -> Status.UNKNOWN_LOCATION_OFF
        wifiName == null -> Status.UNKNOWN
        wifiName == homeWifiName -> Status.ALLOWED
        else -> Status.NEEDS_VPN_OTHER_WIFI
    }

    /** One sentence for the user: why no connection is opened and what helps. */
    fun describe(context: Context, status: Status): String {
        val home = homeWifiName(context)
        return when (status) {
            Status.ALLOWED -> ""
            Status.NEEDS_VPN_NO_WIFI -> context.getString(R.string.network_access_no_wifi, home)
            Status.NEEDS_VPN_OTHER_WIFI -> context.getString(R.string.network_access_other_wifi, home)
            Status.UNKNOWN_NO_PERMISSION -> context.getString(R.string.network_access_no_permission, home)
            Status.UNKNOWN_LOCATION_OFF -> context.getString(R.string.network_access_location_off, home)
            Status.UNKNOWN -> context.getString(R.string.network_access_unknown, home)
        }
    }

    // ---------------------------------------------------------------- Wi-Fi name

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isLocationOn(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /** The name of the Wi-Fi the phone is in now, or null (none, or Android does not tell). Blocks briefly. */
    fun currentWifiName(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return wifiName(context, network)
    }

    private fun wifiName(context: Context, network: Network): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wifiNameFromCallback(context, network)
        } else {
            @Suppress("DEPRECATION")
            val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
            normalizeWifiName(info?.ssid)
        }

    /** Since Android 12 the name is only told to a network callback that asks for it. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun wifiNameFromCallback(context: Context, network: Network): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val result = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        ) {
            override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
                if (changed != network) return
                result.set(normalizeWifiName((capabilities.transportInfo as? WifiInfo)?.ssid))
                done.countDown()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return try {
            connectivity.registerNetworkCallback(request, callback)
            done.await(WIFI_NAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result.get()
        } catch (e: Exception) {
            android.util.Log.w("NetworkAccess", "Wi-Fi name not available", e)
            null
        } finally {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }

    /** Android puts the name in quotes, and says "<unknown ssid>" when it does not tell it. */
    internal fun normalizeWifiName(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == UNKNOWN_SSID) return null
        return if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
    }

    private const val UNKNOWN_SSID = "<unknown ssid>"

    // ---------------------------------------------------------------- the VPN app

    /** The installed VPN apps (package names), apart from this one. */
    @SuppressLint("QueryPermissionsNeeded") // Declared in the manifest (<queries>).
    private fun vpnApps(context: Context): List<String> {
        @Suppress("DEPRECATION")
        val services = context.packageManager.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), 0)
        return services.mapNotNull { it.serviceInfo?.packageName }
            .filter { it != context.packageName }
            .distinct()
    }

    /** With exactly one VPN app: its name and how to open it. Otherwise null (then the VPN settings). */
    fun vpnApp(context: Context): Pair<String, Intent>? {
        val apps = vpnApps(context)
        if (apps.size != 1) return null
        val packageName = apps.single()
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        val label = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
        return label to launch
    }
}
