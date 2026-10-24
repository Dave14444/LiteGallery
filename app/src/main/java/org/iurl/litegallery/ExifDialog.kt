package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iurl.litegallery.theme.ThemeColorResolver
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shows the EXIF data of a picture (camera, exposure, date, location) in a dialog. Works for
 * files on the phone and on network shares; only the beginning of a file is read.
 */
object ExifDialog {

    private class Row(val label: String, val value: String)

    private class Info(val rows: List<Row>, val hasExif: Boolean, val latitude: Double?, val longitude: Double?)

    fun show(activity: AppCompatActivity, path: String, name: String, sizeBytes: Long, modifiedMs: Long) {
        val isRemote = MediaSourceRegistry.forPath(path) != null
        val progressDialog = if (isRemote) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.exif_loading)
                .setCancelable(false)
                .create()
                .also { it.show() }
        } else {
            null
        }

        activity.lifecycleScope.launch {
            val info = try {
                withContext(Dispatchers.IO) { read(activity.applicationContext, path, name, sizeBytes, modifiedMs) }
            } catch (e: Exception) {
                android.util.Log.e("ExifDialog", "Could not read EXIF data of $path", e)
                null
            }
            progressDialog?.dismiss()

            if (info == null) {
                Toast.makeText(activity, R.string.exif_failed, Toast.LENGTH_LONG).show()
            } else {
                display(activity, info)
            }
        }
    }

    // ---------------------------------------------------------------- reading

    private fun read(context: Context, path: String, name: String, sizeBytes: Long, modifiedMs: Long): Info {
        val source = MediaSourceRegistry.forPath(path)
        val exif: ExifInterface? = try {
            when {
                source != null -> source.openInput(context, path).use { ExifInterface(it) }
                path.startsWith("content://") ->
                    context.contentResolver.openInputStream(Uri.parse(path))?.use { ExifInterface(it) }
                else -> ExifInterface(path)
            }
        } catch (e: Exception) {
            null
        }

        val file = if (source == null && !path.startsWith("content://")) File(path) else null
        val size = if (sizeBytes > 0L) sizeBytes else (file?.takeIf { it.exists() }?.length() ?: -1L)
        val modified = if (modifiedMs > 0L) modifiedMs else (file?.takeIf { it.exists() }?.lastModified() ?: 0L)

        val rows = mutableListOf<Row>()
        fun add(labelRes: Int, value: String?) {
            if (!value.isNullOrBlank()) rows.add(Row(context.getString(labelRes), value))
        }

        add(R.string.exif_file_name, name)
        if (size > 0L) add(R.string.exif_file_size, Formatter.formatFileSize(context, size))
        if (modified > 0L) {
            add(R.string.exif_file_modified, DateFormat.getDateTimeInstance().format(Date(modified)))
        }

        var latitude: Double? = null
        var longitude: Double? = null
        val before = rows.size

        if (exif != null) {
            val width = exif.getAttributeInt("ImageWidth", 0).takeIf { it > 0 }
                ?: exif.getAttributeInt("PixelXDimension", 0)
            val height = exif.getAttributeInt("ImageLength", 0).takeIf { it > 0 }
                ?: exif.getAttributeInt("PixelYDimension", 0)
            if (width > 0 && height > 0) add(R.string.exif_dimensions, "$width × $height")

            add(R.string.exif_date_taken, formatTakenDate(exif))

            val make = exif.getAttribute("Make").orEmpty().trim()
            val model = exif.getAttribute("Model").orEmpty().trim()
            val camera = if (model.startsWith(make, ignoreCase = true)) model else "$make $model".trim()
            add(R.string.exif_camera, camera)
            add(R.string.exif_lens, exif.getAttribute("LensModel"))
            add(R.string.exif_software, exif.getAttribute("Software"))

            val exposure = exif.getAttributeDouble("ExposureTime", -1.0)
            if (exposure > 0.0) {
                add(
                    R.string.exif_exposure,
                    if (exposure >= 1.0) {
                        String.format(Locale.getDefault(), "%.1f s", exposure)
                    } else {
                        "1/${(1.0 / exposure).roundToInt()} s"
                    }
                )
            }
            val aperture = exif.getAttributeDouble("FNumber", -1.0)
            if (aperture > 0.0) add(R.string.exif_aperture, String.format(Locale.getDefault(), "f/%.1f", aperture))

            val iso = exif.getAttribute("PhotographicSensitivity") ?: exif.getAttribute("ISOSpeedRatings")
            add(R.string.exif_iso, iso)

            val focal = exif.getAttributeDouble("FocalLength", -1.0)
            if (focal > 0.0) {
                val equivalent = exif.getAttributeInt("FocalLengthIn35mmFilm", 0)
                val focalText = String.format(Locale.getDefault(), "%.0f mm", focal)
                add(
                    R.string.exif_focal_length,
                    if (equivalent > 0) context.getString(R.string.exif_focal_with_35mm, focalText, equivalent) else focalText
                )
            }

            val bias = exif.getAttributeDouble("ExposureBiasValue", Double.NaN)
            if (!bias.isNaN()) add(R.string.exif_exposure_bias, String.format(Locale.getDefault(), "%+.1f EV", bias))

            val flash = exif.getAttributeInt("Flash", -1)
            if (flash >= 0) {
                add(
                    R.string.exif_flash,
                    context.getString(if ((flash and 1) == 1) R.string.exif_flash_fired else R.string.exif_flash_off)
                )
            }
            when (exif.getAttributeInt("WhiteBalance", -1)) {
                0 -> add(R.string.exif_white_balance, context.getString(R.string.exif_wb_auto))
                1 -> add(R.string.exif_white_balance, context.getString(R.string.exif_wb_manual))
            }

            val latLong = exif.latLong
            if (latLong != null && latLong.size == 2) {
                latitude = latLong[0]
                longitude = latLong[1]
                add(
                    R.string.exif_location,
                    String.format(Locale.US, "%.5f, %.5f", latLong[0], latLong[1])
                )
                val altitude = exif.getAltitude(Double.NaN)
                if (!altitude.isNaN()) {
                    add(R.string.exif_altitude, String.format(Locale.getDefault(), "%.0f m", altitude))
                }
            }
        }

        return Info(rows, rows.size > before, latitude, longitude)
    }

    private fun formatTakenDate(exif: ExifInterface): String? {
        val raw = exif.getAttribute("DateTimeOriginal") ?: exif.getAttribute("DateTime") ?: return null
        return try {
            val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)
            if (parsed != null) DateFormat.getDateTimeInstance().format(parsed) else raw
        } catch (e: Exception) {
            raw
        }
    }

    // ---------------------------------------------------------------- display

    private fun display(activity: AppCompatActivity, info: Info) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val primary = ThemeColorResolver.resolveColor(activity, com.google.android.material.R.attr.colorOnSurface)
        val secondary = ThemeColorResolver.resolveColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        info.rows.forEach { row ->
            container.addView(
                TextView(activity).apply {
                    text = row.label
                    textSize = 12f
                    setTextColor(secondary)
                    setPadding(0, dp(10), 0, 0)
                },
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            container.addView(
                TextView(activity).apply {
                    text = row.value
                    textSize = 16f
                    setTextColor(primary)
                    setTextIsSelectable(true)
                },
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        if (!info.hasExif) {
            container.addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.exif_none)
                    textSize = 14f
                    setTextColor(secondary)
                    setPadding(0, dp(16), 0, 0)
                }
            )
        }

        val scroll = ScrollView(activity).apply { addView(container) }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.exif_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)

        val latitude = info.latitude
        val longitude = info.longitude
        if (latitude != null && longitude != null) {
            builder.setNeutralButton(R.string.exif_open_map) { _, _ ->
                try {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude"))
                    )
                } catch (e: Exception) {
                    Toast.makeText(activity, R.string.exif_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        val dialog = builder.create()
        dialog.show()
        ThemeHelper.applyRuntimeCustomColors(dialog)
    }
}
