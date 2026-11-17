package org.iurl.litegallery

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ExternalViewActivityTest {

    @Test
    fun aPictureFromAnotherApp_isPassedOnToTheViewer() {
        val uri = Uri.parse("content://com.example.files/picture/1")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val activity = Robolectric.buildActivity(ExternalViewActivity::class.java, intent).create().get()

        val started = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(MediaViewerActivity::class.java.name, started.component?.className)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(uri, started.data)
        assertEquals("image/jpeg", started.type)
        assertTrue(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun theViewersOwnCommands_areNotPassedOn() {
        // E.g. "open this folder of a network share", sent by another app.
        val intent = Intent()
            .putExtra("extra_folder_path", "smb://192.168.178.20/Haus")
        val activity = Robolectric.buildActivity(ExternalViewActivity::class.java, intent).create().get()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
}
