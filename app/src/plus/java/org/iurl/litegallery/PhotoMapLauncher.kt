package org.iurl.litegallery

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

/** `plus` flavor: the pictures of a folder on an OpenStreetMap map (see PhotoMapActivity). */
object PhotoMapLauncher {
    val isAvailable: Boolean get() = true

    /** Shows the pictures the folder screen of [folderPath] shows now (see FolderMediaRepository). */
    fun open(activity: AppCompatActivity, folderPath: String, title: String) {
        activity.startActivity(
            Intent(activity, PhotoMapActivity::class.java)
                .putExtra(PhotoMapActivity.EXTRA_FOLDER_PATH, folderPath)
                .putExtra(PhotoMapActivity.EXTRA_TITLE, title)
        )
    }
}
