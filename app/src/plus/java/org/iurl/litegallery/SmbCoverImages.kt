package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Own cover pictures for the network connections shown on the home screen. The chosen picture
 * is copied (scaled down) into the app's private storage.
 */
object SmbCoverImages {

    private const val PREFS = "smb_cover_images"
    private const val DIRECTORY = "smb_covers"
    private const val MAX_SIDE = 960

    /** Path of the cover picture of the connection [serverId], or null if none was chosen. */
    fun coverPath(context: Context, serverId: String): String? {
        val name = prefs(context).getString(serverId, null) ?: return null
        val file = File(directory(context), name)
        return if (file.isFile) file.absolutePath else null
    }

    fun remove(context: Context, serverId: String) {
        val name = prefs(context).getString(serverId, null)
        if (name != null) File(directory(context), name).delete()
        prefs(context).edit().remove(serverId).apply()
    }

    /** Dialog shown when a connection is long-pressed on the home screen. */
    fun showOptions(
        activity: AppCompatActivity,
        server: NetworkServerConfig,
        onHide: () -> Unit,
        onChanged: () -> Unit
    ) {
        val hasCover = coverPath(activity, server.id) != null
        val labels = mutableListOf(activity.getString(R.string.smb_cover_choose))
        if (hasCover) labels.add(activity.getString(R.string.smb_cover_reset))
        labels.add(activity.getString(R.string.hide_folder))

        val resetIndex = if (hasCover) 1 else -1
        AlertDialog.Builder(activity)
            .setTitle(server.displayName.ifBlank { server.host })
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> pickImage(activity, server, onChanged)
                    resetIndex -> {
                        remove(activity, server.id)
                        onChanged()
                    }
                    else -> onHide()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickImage(
        activity: AppCompatActivity,
        server: NetworkServerConfig,
        onChanged: () -> Unit
    ) {
        lateinit var launcher: ActivityResultLauncher<String>
        launcher = activity.activityResultRegistry.register(
            "smb_cover_${server.id}_${System.nanoTime()}",
            ActivityResultContracts.GetContent()
        ) { uri ->
            launcher.unregister()
            if (uri != null) {
                activity.lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        saveFromUri(activity.applicationContext, server.id, uri)
                    }
                    if (saved) {
                        onChanged()
                    } else {
                        Toast.makeText(activity, R.string.smb_cover_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        launcher.launch("image/*")
    }

    private fun saveFromUri(context: Context, serverId: String, uri: Uri): Boolean {
        return try {
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // With inJustDecodeBounds the call returns no bitmap (null), only the size in [bounds].
            val boundsStream = resolver.openInputStream(uri) ?: return false
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= MAX_SIDE &&
                bounds.outHeight / (sampleSize * 2) >= MAX_SIDE
            ) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return false

            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
            val oriented = ImageOrientation.apply(decoded, orientation)

            val scale = MAX_SIDE.toFloat() / maxOf(oriented.width, oriented.height)
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * scale).toInt().coerceAtLeast(1),
                    (oriented.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                oriented
            }

            val directory = directory(context)
            if (!directory.isDirectory) directory.mkdirs()
            // A new file name each time, so image caches never show the previous picture.
            val target = File(directory, "${serverId}_${System.currentTimeMillis()}.jpg")
            target.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }

            val previous = prefs(context).getString(serverId, null)
            prefs(context).edit().putString(serverId, target.name).apply()
            if (previous != null && previous != target.name) {
                File(directory, previous).delete()
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("SmbCoverImages", "Could not save cover image", e)
            false
        }
    }

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
