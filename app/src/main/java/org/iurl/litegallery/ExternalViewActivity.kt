package org.iurl.litegallery

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The way in for other apps ("Open with LiteGallery"): shows the one picture or video they hand
 * over, and nothing else.
 *
 * The viewer itself is not open to other apps: it also takes the app's own commands (e.g. "open
 * this folder of a network share"), which another app must not be able to send. This screen only
 * passes on the file to view, with the permission to read it, and closes again at once.
 */
class ExternalViewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null) {
            val forward = Intent(this, MediaViewerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(data, intent.type)
            // Only what the other app granted is passed on (granting more would fail).
            val granted = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            forward.addFlags(granted)
            intent.clipData?.let { forward.clipData = it }
            try {
                startActivity(forward)
            } catch (e: Exception) {
                android.util.Log.w("ExternalViewActivity", "Could not open the file", e)
            }
        }
        finish()
    }
}
