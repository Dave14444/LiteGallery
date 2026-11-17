package org.iurl.litegallery

import android.os.Bundle
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.iurl.litegallery.theme.ThemeVariant

/**
 * Covers the app while it is locked (see [AppLock]) and asks for fingerprint, face or the device
 * PIN. Back leaves the app; it stays locked.
 */
class LockActivity : AppCompatActivity() {

    /** The task this lock screen covers (still known while it is destroyed). */
    var shownInTask = -1
        private set

    private lateinit var prompt: BiometricPrompt
    private var prompting = false
    /** Asks by itself once each time the lock screen comes up; after "Cancel" the button asks. */
    private var askWhenShown = true
    /** The PIN screen of the system was opened from here: coming back from it is no new showing. */
    private var leftForPin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        ThemeHelper.applyPackTheme(this, ThemeVariant.NoActionBar)
        super.onCreate(savedInstanceState)
        shownInTask = taskId
        setContentView(R.layout.activity_lock)
        ThemeHelper.applyRuntimeCustomColors(this)

        prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    AppLock.unlock()
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled, or too many attempts: the lock screen stays, the button asks again.
                    prompting = false
                    AppLock.backFromOtherApp()
                }
            }
        )

        findViewById<Button>(R.id.unlockButton).setOnClickListener { ask() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Leave the app; it stays locked.
                    moveTaskToBack(true)
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        askWhenShown = !leftForPin
        leftForPin = false
    }

    override fun onStop() {
        super.onStop()
        // Stopped while asking: the PIN screen of the system is in front.
        leftForPin = prompting
    }

    override fun onResume() {
        super.onResume()
        if (!AppLock.isLocked) {
            // Unlocked meanwhile (e.g. in another window of the app).
            finish()
            return
        }
        if (askWhenShown && !prompting) {
            askWhenShown = false
            ask()
        }
    }

    private fun ask() {
        if (prompting) return
        if (!AppLock.canAuthenticate(this)) {
            // No fingerprint, face or screen lock on the phone any more: nothing to check against.
            AppLock.turnOffWithoutScreenLock(this)
            finish()
            return
        }
        prompting = true
        // Entering the PIN opens a screen of the system: coming back from it does not lock again.
        AppLock.leavingForOtherApp()
        prompt.authenticate(AppLock.promptInfo(this))
    }
}
