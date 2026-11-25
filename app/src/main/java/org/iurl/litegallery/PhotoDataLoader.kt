package org.iurl.litegallery

import android.app.AlertDialog
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gets the photo data of a folder's pictures for a screen (filter, map): at once if known, else
 * read with "Reading photo data… 120 of 400" and Cancel (shown only if it takes a moment).
 */
object PhotoDataLoader {

    private const val SHOW_PROGRESS_AFTER_MS = 300L

    fun load(
        activity: AppCompatActivity,
        folderPath: String,
        items: List<MediaItemSkeleton>,
        onLoaded: (Map<String, PhotoInfo>) -> Unit
    ) {
        val cancelled = AtomicBoolean(false)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 1000
        }
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 3, padding, 0)
            addView(bar)
        }
        var job: Job? = null
        val dialog = AlertDialog.Builder(activity)
            .setMessage(R.string.photo_data_reading)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ ->
                cancelled.set(true)
                job?.cancel()
            }
            .create()
        val appContext = activity.applicationContext
        job = activity.lifecycleScope.launch {
            val showDialog = launch {
                delay(SHOW_PROGRESS_AFTER_MS)
                dialog.show()
                ThemeHelper.applyRuntimeCustomColors(dialog)
            }
            val infos = try {
                withContext(Dispatchers.IO) {
                    val worker = this
                    PhotoData.load(
                        appContext,
                        folderPath,
                        items,
                        keepGoing = { worker.isActive && !cancelled.get() }
                    ) { done, total ->
                        activity.runOnUiThread {
                            if (!dialog.isShowing || total <= 0) return@runOnUiThread
                            bar.isIndeterminate = false
                            bar.progress = (done * 1000L / total).toInt()
                            dialog.setMessage(activity.getString(R.string.photo_data_reading_progress, done, total))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("PhotoDataLoader", "Photo data of $folderPath not readable", e)
                Toast.makeText(activity, R.string.photo_data_failed, Toast.LENGTH_LONG).show()
                null
            } finally {
                showDialog.cancel()
                dialog.dismiss()
            }
            if (infos != null && !cancelled.get()) onLoaded(infos)
        }
    }
}
