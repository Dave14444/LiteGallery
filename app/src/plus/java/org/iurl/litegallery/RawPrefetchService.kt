package org.iurl.litegallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads the camera RAW files of a picture folder onto the device in the background (see
 * RawOffline), with progress in a notification and "Cancel". Files that are there already are
 * skipped; each file that arrived counts even if the run is stopped.
 */
class RawPrefetchService : Service() {

    companion object {
        private const val CHANNEL_ID = "raw_prefetch"
        private const val NOTIFICATION_ID = 4712
        private const val ACTION_STOP = "org.iurl.litegallery.action.STOP_RAW_PREFETCH"
        private const val EXTRA_FOLDER = "folder"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_FILES = "files"

        /** Kept free on the device besides the RAW files. */
        internal const val SPACE_RESERVE_BYTES = 500L * 1024L * 1024L

        /** How often the notification is updated. */
        private const val NOTIFY_EVERY_MS = 700L

        /** Android stops data-sync services after 6 hours; stop a little earlier ourselves. */
        private const val MAX_RUN_MS = 5L * 60L * 60L * 1000L + 30L * 60L * 1000L

        @Volatile
        var isRunning = false
            private set

        /** Loads [files] (RAW files of the pictures in [folderPath], by picture name) onto the device. */
        fun start(context: Context, folderPath: String, label: String, files: Map<String, RawCompanion.Entry>) {
            val list = JSONArray()
            files.forEach { (picture, entry) ->
                list.put(
                    JSONObject()
                        .put("picture", picture)
                        .put("raw", entry.path)
                        .put("name", entry.name)
                        .put("size", entry.size)
                )
            }
            val intent = Intent(context, RawPrefetchService::class.java)
                .putExtra(EXTRA_FOLDER, folderPath)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_FILES, list.toString())
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, RawPrefetchService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                android.util.Log.w("RawPrefetch", "Could not stop", e)
            }
        }
    }

    private class Summary(val loaded: Int, val alreadyThere: Int, val failed: Int, val noSpace: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var label = ""

    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        if (intent?.action == ACTION_STOP) {
            stopNow()
            return START_NOT_STICKY
        }
        val folderPath = intent?.getStringExtra(EXTRA_FOLDER)
        val files = intent?.getStringExtra(EXTRA_FILES)?.let { parseFiles(it) }.orEmpty()
        val newLabel = intent?.getStringExtra(EXTRA_LABEL).orEmpty()

        // Must be called promptly after startForegroundService().
        if (!isRunning) label = newLabel
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            progressNotification(null, 0, files.size, 0L, 0L),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        // One folder at a time (the screen offers to stop a run that is going).
        if (isRunning) return START_NOT_STICKY
        if (folderPath == null || files.isEmpty()) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        stopping = false
        job = scope.launch {
            var summary: Summary? = null
            try {
                summary = withTimeoutOrNull(MAX_RUN_MS) { loadAll(folderPath, files) }
            } catch (e: CancellationException) {
                // Stopped by the user.
            } catch (e: Exception) {
                android.util.Log.e("RawPrefetch", "Loading RAW files failed", e)
            } finally {
                isRunning = false
                if (!stopping) {
                    ServiceCompat.stopForeground(this@RawPrefetchService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    if (summary != null) {
                        notifyDone(folderPath, summary)
                    } else {
                        NotificationManagerCompat.from(this@RawPrefetchService).cancel(NOTIFICATION_ID)
                    }
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun parseFiles(json: String): List<RawOffline.Kept> = try {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            RawOffline.Kept(
                picture = item.getString("picture"),
                rawPath = item.getString("raw"),
                name = item.getString("name"),
                size = item.optLong("size", -1L)
            )
        }
    } catch (e: Exception) {
        android.util.Log.e("RawPrefetch", "Bad file list", e)
        emptyList()
    }

    private suspend fun loadAll(folderPath: String, files: List<RawOffline.Kept>): Summary {
        val context = applicationContext
        var loaded = 0
        var alreadyThere = 0
        var failed = 0
        val totalBytes = files.sumOf { it.size.coerceAtLeast(0L) }
        var doneBytes = 0L
        var notifiedAt = 0L

        // Copies that are complete already count again (and their days start anew).
        val present = files.filter { RawOffline.has(context, folderPath, it.rawPath, it.size) }
        if (present.isNotEmpty()) RawOffline.record(context, folderPath, present)
        alreadyThere = present.size
        doneBytes += present.sumOf { it.size.coerceAtLeast(0L) }

        for ((index, kept) in files.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (kept in present) continue
            val source = MediaSourceRegistry.forPath(kept.rawPath)
            if (source == null) {
                failed++
                continue
            }
            val target = RawOffline.fileFor(context, folderPath, kept.name)
            val part = RawOffline.partFileFor(context, folderPath, kept.name)
            target.parentFile?.mkdirs()
            if (kept.size > 0L && part.parentFile!!.usableSpace < kept.size + SPACE_RESERVE_BYTES) {
                return Summary(loaded, alreadyThere, failed + (files.size - index), noSpace = true)
            }
            try {
                source.openInput(context, kept.rawPath).use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            doneBytes += count
                            val now = SystemClock.elapsedRealtime()
                            if (now - notifiedAt >= NOTIFY_EVERY_MS) {
                                notifiedAt = now
                                updateNotification(kept.name, index + 1, files.size, doneBytes, totalBytes)
                            }
                        }
                    }
                }
                if (kept.size > 0L && part.length() != kept.size) {
                    throw java.io.IOException("Incomplete: ${part.length()} of ${kept.size} bytes")
                }
                target.delete()
                if (!part.renameTo(target)) throw java.io.IOException("Could not rename $part")
                RawOffline.record(context, folderPath, listOf(kept))
                loaded++
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                android.util.Log.w("RawPrefetch", "Could not load ${kept.rawPath}", e)
                part.delete()
                failed++
            }
        }
        return Summary(loaded, alreadyThere, failed, noSpace = false)
    }

    private fun stopNow() {
        stopping = true
        isRunning = false
        job?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        isRunning = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.raw_prefetch_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun progressNotification(fileName: String?, number: Int, count: Int, doneBytes: Long, totalBytes: Long): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RawPrefetchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_raw)
            .setContentTitle(getString(R.string.raw_prefetch_title))
            .setSubText(label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.cancel), stopIntent)
        if (fileName == null || totalBytes <= 0L) {
            builder.setContentText(label).setProgress(0, 0, true)
        } else {
            builder.setContentText(
                getString(
                    R.string.raw_prefetch_progress,
                    number,
                    count,
                    Formatter.formatShortFileSize(this, doneBytes),
                    Formatter.formatShortFileSize(this, totalBytes)
                )
            )
            builder.setProgress(1000, (doneBytes * 1000L / totalBytes).coerceIn(0L, 1000L).toInt(), false)
        }
        return builder.build()
    }

    private fun updateNotification(fileName: String, number: Int, count: Int, doneBytes: Long, totalBytes: Long) {
        if (stopping) return
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, progressNotification(fileName, number, count, doneBytes, totalBytes))
        } catch (e: SecurityException) {
            // Notifications are not allowed: the work continues without them.
        }
    }

    private fun notifyDone(folderPath: String, summary: Summary) {
        val until = RawOffline.read(applicationContext, folderPath)?.expiresAt
            ?.let { android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(it)) }
            .orEmpty()
        var text = getString(R.string.raw_prefetch_done, summary.loaded, summary.alreadyThere, summary.failed, until)
        if (summary.noSpace) text = getString(R.string.raw_prefetch_stopped_space) + "\n" + text
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_raw)
            .setContentTitle(getString(R.string.raw_prefetch_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(label)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications are not allowed.
        }
    }
}

/** Starts loading the RAW files of a folder from a screen: looks them up, shows size and space, asks. */
object RawPrefetcher {

    fun start(activity: androidx.appcompat.app.AppCompatActivity, folderPath: String, label: String) {
        if (RawPrefetchService.isRunning) {
            android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.raw_prefetch_title)
                .setMessage(R.string.raw_prefetch_running)
                .setPositiveButton(R.string.raw_prefetch_stop) { _, _ -> RawPrefetchService.stop(activity.applicationContext) }
                .setNegativeButton(R.string.raw_prefetch_continue, null)
                .show()
            return
        }
        val source = MediaSourceRegistry.forPath(folderPath) ?: return
        var search: Job? = null
        val searching = android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.raw_prefetch_title)
            .setMessage(R.string.raw_prefetch_searching)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> search?.cancel() }
            .create()
        searching.show()
        ThemeHelper.applyRuntimeCustomColors(searching)
        val context = activity.applicationContext
        search = activity.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val worker = this
                    Result.success(
                        RawCompanion.collect(folderPath, keepGoing = { worker.isActive }) { path ->
                            source.listFolder(context, path).map {
                                RawCompanion.Entry(it.name, it.path, it.isDirectory, it.size)
                            }
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                searching.dismiss()
            }
            val found = result.getOrElse { e ->
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.raw_prefetch_failed_search, e.message ?: e.javaClass.simpleName),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            confirm(activity, folderPath, label, found)
        }
    }

    private fun confirm(
        activity: androidx.appcompat.app.AppCompatActivity,
        folderPath: String,
        label: String,
        found: Map<String, RawCompanion.Entry>
    ) {
        val context = activity.applicationContext
        if (found.isEmpty()) {
            android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.raw_prefetch_title)
                .setMessage(R.string.raw_prefetch_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val kept = RawOffline.read(context, folderPath)?.takeUnless { RawOffline.isExpired(it) }
        val missing = found.filter { (_, entry) -> !RawOffline.has(context, folderPath, entry.path, entry.size) }
        val missingBytes = missing.values.sumOf { it.size.coerceAtLeast(0L) }
        val free = RawOffline.root(context).usableSpace
        val date = { millis: Long -> android.text.format.DateFormat.getMediumDateFormat(activity).format(java.util.Date(millis)) }

        val message = StringBuilder(activity.getString(R.string.raw_prefetch_found, found.size))
        val alreadyThere = found.size - missing.size
        if (kept != null && alreadyThere > 0) {
            message.append("\n").append(activity.getString(R.string.raw_prefetch_already, alreadyThere, date(kept.expiresAt)))
        }
        if (missing.isNotEmpty()) {
            message.append("\n\n").append(
                activity.getString(
                    R.string.raw_prefetch_to_load,
                    missing.size,
                    Formatter.formatShortFileSize(activity, missingBytes),
                    Formatter.formatShortFileSize(activity, free)
                )
            )
        }
        message.append("\n\n").append(activity.getString(R.string.raw_prefetch_keep, RawOffline.KEEP_DAYS))

        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.raw_prefetch_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
        if (missing.isNotEmpty()) {
            dialog.setPositiveButton(R.string.raw_prefetch_start) { _, _ ->
                if (missingBytes + RawPrefetchService.SPACE_RESERVE_BYTES > free) {
                    android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.raw_prefetch_title)
                        .setMessage(
                            activity.getString(
                                R.string.raw_prefetch_no_space,
                                Formatter.formatShortFileSize(activity, missingBytes),
                                Formatter.formatShortFileSize(activity, free)
                            )
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    requestNotificationsThenStart(activity, folderPath, label, found)
                }
            }
        } else {
            // All there: the days start anew.
            dialog.setPositiveButton(activity.getString(R.string.raw_prefetch_extend, RawOffline.KEEP_DAYS)) { _, _ ->
                val all = found.map { (picture, entry) -> RawOffline.Kept(picture, entry.path, entry.name, entry.size) }
                RawOffline.record(context, folderPath, all)
                val until = RawOffline.read(context, folderPath)?.expiresAt ?: 0L
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.raw_prefetch_extended, date(until)),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        if (kept != null) {
            dialog.setNeutralButton(R.string.raw_prefetch_delete) { _, _ ->
                RawOffline.remove(context, folderPath)
                android.widget.Toast.makeText(activity, R.string.raw_prefetch_deleted, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        dialog.show().also { ThemeHelper.applyRuntimeCustomColors(it) }
    }

    private fun requestNotificationsThenStart(
        activity: androidx.appcompat.app.AppCompatActivity,
        folderPath: String,
        label: String,
        found: Map<String, RawCompanion.Entry>
    ) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!needsPermission) {
            begin(activity, folderPath, label, found)
            return
        }
        // Without the permission the work still runs, only the progress notification is hidden.
        lateinit var launcher: androidx.activity.result.ActivityResultLauncher<String>
        launcher = activity.activityResultRegistry.register(
            "raw_prefetch_notifications_${System.nanoTime()}",
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { _ ->
            launcher.unregister()
            begin(activity, folderPath, label, found)
        }
        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun begin(
        activity: androidx.appcompat.app.AppCompatActivity,
        folderPath: String,
        label: String,
        found: Map<String, RawCompanion.Entry>
    ) {
        try {
            RawPrefetchService.start(activity.applicationContext, folderPath, label, found)
            android.widget.Toast.makeText(activity, R.string.raw_prefetch_started, android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.util.Log.e("RawPrefetch", "Could not start", e)
            android.widget.Toast.makeText(
                activity,
                activity.getString(R.string.raw_prefetch_failed_search, e.message ?: e.javaClass.simpleName),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
