package org.iurl.litegallery

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * The app lock: after LiteGallery was in the background for the chosen time (switching the screen
 * off counts as leaving it), it shows nothing until it is unlocked with fingerprint, face or the
 * device PIN.
 *
 * Works for every screen of the app without them knowing: each screen that comes to the front
 * while the app is locked is covered and [LockActivity] is put on top of it. Trips to other apps
 * that LiteGallery itself starts (editing in Snapseed, sharing, choosing a folder) do not lock it,
 * see [leavingForOtherApp].
 */
object AppLock : Application.ActivityLifecycleCallbacks {

    /** Setting: "off", or the seconds in the background after which the app locks ("0": at once). */
    const val KEY_TIMEOUT = "app_lock_timeout"
    const val OFF = "off"

    /** Fingerprint or face, and the device PIN, pattern or password (so nobody is locked out). */
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** A trip to another app that LiteGallery started does not lock it, if back within this time... */
    private const val TRIP_MAX_MS = 30 * 60 * 1000L

    /** ...and if the app went to the background within this time after starting it (e.g. choosing in the share menu). */
    private const val TRIP_START_MS = 2 * 60 * 1000L

    private var appContext: Context? = null

    /** Screens of the app that are started (visible); 0 means the app is in the background. */
    private var started = 0

    /** Since when the app is in the background (elapsed time), 0 while it is in front. */
    private var backgroundSince = 0L

    /** When the app itself last opened another app (elapsed time), 0 if not. */
    private var tripStartedAt = 0L

    /** Tasks that have a lock screen on top already. */
    private val tasksWithLockScreen = HashSet<Int>()

    /** Whether the app has to be unlocked before anything is shown. */
    @Volatile
    var isLocked = false
        private set

    fun install(app: Application) {
        appContext = app
        // A fresh start of the app is locked.
        isLocked = isEnabled(app)
        app.registerActivityLifecycleCallbacks(this)
        // Screen off: a trip to another app is over; from now on the chosen time counts.
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    tripStartedAt = 0L
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // ---------------------------------------------------------------- settings

    private fun setting(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_TIMEOUT, OFF) ?: OFF

    fun isEnabled(context: Context): Boolean = timeoutMillis(setting(context)) != null

    /** The time in the background after which the app locks; null if the lock is off. */
    internal fun timeoutMillis(value: String): Long? =
        if (value == OFF) null else value.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)

    /** Whether the phone can check the owner (fingerprint, face, or at least a screen lock PIN). */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun promptInfo(context: Context, title: String = context.getString(R.string.app_lock_prompt_title)): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

    // ---------------------------------------------------------------- locking and unlocking

    /**
     * Call right before LiteGallery opens another app or a screen of the system (an editing app,
     * the share menu, the file picker, the PIN screen). Coming back from there does not lock.
     */
    fun leavingForOtherApp() {
        tripStartedAt = SystemClock.elapsedRealtime()
    }

    /** The other app or system screen is done (e.g. the PIN was entered): leaving now locks again. */
    fun backFromOtherApp() {
        tripStartedAt = 0L
    }

    fun unlock() {
        isLocked = false
        backgroundSince = 0L
        tripStartedAt = 0L
    }

    /** No fingerprint, face or screen lock left on the phone: the lock cannot work any more. */
    fun turnOffWithoutScreenLock(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY_TIMEOUT, OFF).apply()
        unlock()
        Toast.makeText(context, R.string.app_lock_turned_off_no_screen_lock, Toast.LENGTH_LONG).show()
    }

    /** Whether the app locks when it comes back after [backgroundSince] (all elapsed times in ms). */
    internal fun locksOnReturn(backgroundSince: Long, now: Long, tripStartedAt: Long, timeoutMs: Long): Boolean {
        val away = now - backgroundSince
        val trip = tripStartedAt != 0L &&
            backgroundSince - tripStartedAt in 0..TRIP_START_MS &&
            away <= TRIP_MAX_MS
        return !trip && away >= timeoutMs
    }

    private fun cameToFront() {
        val context = appContext ?: return
        val since = backgroundSince
        backgroundSince = 0L
        val trip = tripStartedAt
        tripStartedAt = 0L
        if (since == 0L) return
        val timeout = timeoutMillis(setting(context)) ?: return
        if (locksOnReturn(since, SystemClock.elapsedRealtime(), trip, timeout)) isLocked = true
    }

    private fun needsUnlock(activity: Activity): Boolean = isLocked && isEnabled(activity)

    /** Nothing of the screen is shown until it is unlocked (not even for a moment). */
    private fun cover(activity: Activity) {
        activity.window?.decorView?.alpha = 0f
    }

    private fun uncover(activity: Activity) {
        activity.window?.decorView?.alpha = 1f
    }

    private fun showLockScreen(activity: Activity) {
        if (!tasksWithLockScreen.add(activity.taskId)) return
        try {
            activity.startActivity(
                Intent(activity, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            tasksWithLockScreen.remove(activity.taskId)
            android.util.Log.e("AppLock", "Lock screen could not be shown", e)
        }
    }

    /** Since Android 13 the preview in the list of recent apps can be hidden (screenshots still work). */
    private fun protectPreview(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!isEnabled(activity))
        }
    }

    // ---------------------------------------------------------------- the screens of the app

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        protectPreview(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        if (started == 1) cameToFront()
        if (activity !is LockActivity && needsUnlock(activity)) {
            cover(activity)
            showLockScreen(activity)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        protectPreview(activity)
        if (activity is LockActivity) return
        if (needsUnlock(activity)) {
            cover(activity)
            showLockScreen(activity)
        } else {
            uncover(activity)
            // Back in the app without having left it (e.g. the share menu was closed).
            tripStartedAt = 0L
        }
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        // Turning the phone (the screen is made anew) is no leaving.
        if (started == 0 && !activity.isChangingConfigurations) {
            backgroundSince = SystemClock.elapsedRealtime().coerceAtLeast(1L)
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is LockActivity && !activity.isChangingConfigurations) {
            tasksWithLockScreen.remove(activity.shownInTask)
        }
    }
}
