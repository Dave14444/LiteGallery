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

    /** Covers are stored at most 960 px wide; anything far bigger is not a cover from this app. */
    private const val MAX_IMPORTED_COVER_BYTES = 5 * 1024 * 1024

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
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        entries.add(activity.getString(R.string.smb_cover_choose) to { pickImage(activity, server, onChanged) })
        if (coverPath(activity, server.id) != null) {
            entries.add(
                activity.getString(R.string.smb_cover_reset) to {
                    remove(activity, server.id)
                    onChanged()
                }
            )
        }
        SmbTrash.shareRootOf(server.rootUrl())?.let { shareRoot ->
            entries.add(
                activity.getString(R.string.trash_bin_title) to {
                    activity.startActivity(
                        android.content.Intent(activity, NetworkTrashActivity::class.java)
                            .putExtra(NetworkTrashActivity.EXTRA_SHARE_URL, shareRoot)
                    )
                }
            )
        }
        if (SmbPath.isSmb(server.rootUrl())) {
            entries.add(
                activity.getString(R.string.thumb_prepare_menu) to {
                    ThumbnailPreparer.start(
                        activity,
                        server.rootUrl(),
                        server.displayName.ifBlank { server.host }
                    )
                }
            )
        }
        if (SmbPath.parse(server.rootUrl()) != null) {
            entries.add(activity.getString(R.string.smb_write_test_menu) to { SmbWriteTest.start(activity, server.rootUrl()) })
        }
        entries.add(activity.getString(R.string.hide_folder) to { onHide() })

        AlertDialog.Builder(activity)
            .setTitle(server.displayName.ifBlank { server.host })
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
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
            if (oriented !== decoded) decoded.recycle()

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
            if (bitmap !== oriented) oriented.recycle()
            bitmap.recycle()

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

    /** The cover of [serverId] as Base64 text for the settings file, or null if it has none. */
    fun exportCover(context: Context, serverId: String): String? {
        val path = coverPath(context, serverId) ?: return null
        return try {
            android.util.Base64.encodeToString(File(path).readBytes(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** Stores a cover from the settings file for [serverId]. Returns true on success. */
    fun importCover(context: Context, serverId: String, base64: String): Boolean {
        return try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
            if (bytes.isEmpty() || bytes.size > MAX_IMPORTED_COVER_BYTES) return false

            val directory = directory(context)
            if (!directory.isDirectory) directory.mkdirs()
            val target = File(directory, "${serverId}_${System.currentTimeMillis()}.jpg")
            target.writeBytes(bytes)

            val previous = prefs(context).getString(serverId, null)
            prefs(context).edit().putString(serverId, target.name).apply()
            if (previous != null && previous != target.name) File(directory, previous).delete()
            true
        } catch (e: Exception) {
            android.util.Log.w("SmbCoverImages", "Could not take over the cover of $serverId", e)
            false
        }
    }

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
