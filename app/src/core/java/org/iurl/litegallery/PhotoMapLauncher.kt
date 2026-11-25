package org.iurl.litegallery

import androidx.appcompat.app.AppCompatActivity

/**
 * `core` flavor: no map. A map needs its pieces from the internet, and the core build has no
 * network access at all.
 */
object PhotoMapLauncher {
    val isAvailable: Boolean get() = false

    @Suppress("UNUSED_PARAMETER")
    fun open(activity: AppCompatActivity, folderPath: String, title: String) {
        // Intentionally empty (see above).
    }
}
