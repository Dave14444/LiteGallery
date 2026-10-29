package org.iurl.litegallery

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.iurl.litegallery.theme.ThemeColorResolver
import java.util.Calendar

/**
 * Changes the date the gallery sorts by, for one or several pictures and videos: either to the
 * date taken from their EXIF data, or to a date and time chosen by hand (e.g. for pictures saved
 * from Snapchat, which have no date taken).
 */
object DateEditDialog {

    /** A file whose date is changed; [dateMs] is its current date (start of the date picker). */
    class Target(val path: String, val dateMs: Long)

    /** Gap between the files when several get a date chosen by hand: they keep their order. */
    private const val STEP_MS = 1_000L

    fun show(activity: AppCompatActivity, targets: List<Target>, onChanged: () -> Unit) {
        val files = targets.filter { MediaDates.canChange(it.path) }
        if (files.isEmpty()) return
        // Changing files on the phone needs all-files access.
        if (files.any { FileTransfer.isLocalPath(it.path) } && !StorageAccess.ensure(activity)) return

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val secondary = ThemeColorResolver.resolveColor(
            activity, com.google.android.material.R.attr.colorOnSurfaceVariant
        )

        val writeExif = CheckBox(activity).apply {
            text = activity.getString(R.string.date_edit_write_exif)
            isChecked = true
            // Only JPEG files can carry the date in their EXIF data.
            visibility = if (files.any { MediaDates.canWriteExif(it.path) }) android.view.View.VISIBLE else android.view.View.GONE
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(
                TextView(activity).apply {
                    text = activity.getString(
                        if (files.size == 1) R.string.date_edit_hint else R.string.date_edit_hint_multiple
                    )
                    setTextColor(secondary)
                }
            )
            addView(writeExif)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.date_edit_title)
            .setView(content)
            .setPositiveButton(R.string.date_edit_pick) { _, _ ->
                pickDateTime(activity, files.first().dateMs) { millis ->
                    run(activity, files, chosenMillis = millis, writeExif = writeExif.isChecked, onChanged = onChanged)
                }
            }
            .setNeutralButton(R.string.date_edit_from_exif) { _, _ ->
                // The date comes from the EXIF data already: nothing to write there.
                run(activity, files, chosenMillis = null, writeExif = false, onChanged = onChanged)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        ThemeHelper.applyRuntimeCustomColors(dialog)
    }

    private fun pickDateTime(activity: AppCompatActivity, startMs: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = if (startMs > 0L) startMs else System.currentTimeMillis()
        }
        DatePickerDialog(
            activity,
            { _, year, month, day ->
                calendar.set(year, month, day)
                TimePickerDialog(
                    activity,
                    { _, hour, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        onPicked(calendar.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(activity)
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** [chosenMillis] null: take each file's date from its EXIF data. */
    private fun run(
        activity: AppCompatActivity,
        files: List<Target>,
        chosenMillis: Long?,
        writeExif: Boolean,
        onChanged: () -> Unit
    ) {
        FileOperationDialogs.runWithProgress(
            activity = activity,
            titleRes = R.string.date_edit_running,
            total = files.size,
            work = { onProgress ->
                val context = activity.applicationContext
                var done = 0
                var cancelled = false
                val failures = mutableListOf<FileTransfer.Failure>()
                for ((index, file) in files.withIndex()) {
                    if (OperationCancel.isRequested()) {
                        cancelled = true
                        break
                    }
                    val millis = if (chosenMillis != null) {
                        chosenMillis + index * STEP_MS
                    } else {
                        MediaDates.readTakenDate(context, file.path)
                    }
                    if (millis == null) {
                        failures.add(FileTransfer.Failure(file.path, FileTransfer.Reason.NO_DATE_TAKEN))
                    } else {
                        val ok = try {
                            MediaDates.setDate(context, file.path, millis, writeExif)
                        } catch (e: Exception) {
                            android.util.Log.e("DateEditDialog", "Could not change the date of ${file.path}", e)
                            false
                        }
                        if (ok) done++ else failures.add(FileTransfer.Failure(file.path, FileTransfer.Reason.OTHER))
                    }
                    onProgress(index + 1, files.size)
                }
                FileTransfer.Result(done, failures.size, emptyList(), emptyList(), failures, cancelled)
            },
            onDone = { result -> if (result.done > 0) onChanged() }
        )
    }
}
