package org.iurl.litegallery

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Creates the thumbnails of all pictures and videos below a folder of a network share in the
 * background, so they are on the share (and quick to show) before they are needed - for
 * example before going on a trip with a slow connection. Thumbnails that already exist are
 * skipped, so a run can be stopped and started again at any time.
 */
class ThumbnailPrepareService : Service() {

    companion object {
        private const val CHANNEL_ID = "thumbnail_prepare"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "org.iurl.litegallery.action.STOP_PREPARE_THUMBNAILS"
        private const val EXTRA_FOLDER_URL = "folder_url"
        private const val EXTRA_LABEL = "label"

        /** Pictures processed at the same time (see also SmbThumbnailCache.MAX_PARALLEL_CREATIONS). */
        private const val PARALLEL_FILES = 3

        /** Android stops data-sync services after 6 hours; stop a little earlier ourselves. */
        private const val MAX_RUN_MS = 5L * 60L * 60L * 1000L + 30L * 60L * 1000L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context, folderUrl: String, label: String) {
            val intent = Intent(context, ThumbnailPrepareService::class.java)
                .putExtra(EXTRA_FOLDER_URL, folderUrl)
                .putExtra(EXTRA_LABEL, label)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var label = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }

        label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        // Must be called promptly after startForegroundService().
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            progressNotification(0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val folderUrl = intent?.getStringExtra(EXTRA_FOLDER_URL)
        if (folderUrl == null || job?.isActive == true) {
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        job = scope.launch {
            var summary: Triple<Int, Int, Int>? = null
            try {
                summary = withTimeoutOrNull(MAX_RUN_MS) { prepareAll(folderUrl) }
            } catch (e: CancellationException) {
                // Stopped by the user.
            } catch (e: Exception) {
                android.util.Log.e("ThumbnailPrepare", "Preparing thumbnails failed", e)
            } finally {
                isRunning = false
                ServiceCompat.stopForeground(this@ThumbnailPrepareService, ServiceCompat.STOP_FOREGROUND_DETACH)
                if (summary != null) {
                    notifyDone(summary.first, summary.second, summary.third)
                } else {
                    NotificationManagerCompat.from(this@ThumbnailPrepareService).cancel(NOTIFICATION_ID)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        isRunning = false
        super.onDestroy()
    }

    /** Returns (created, alreadyThere, failed). */
    private suspend fun prepareAll(folderUrl: String): Triple<Int, Int, Int> {
        val created = AtomicInteger()
        val alreadyThere = AtomicInteger()
        val failed = AtomicInteger()
        val checked = AtomicInteger()
        val cachedNamesByShare = HashMap<String, Set<String>>()
        val queue = ArrayDeque<String>()
        queue.add(folderUrl)

        // Several pictures are processed at the same time: most of the time is spent waiting
        // for the network, so this is much faster than one picture after the other.
        val permits = Semaphore(PARALLEL_FILES)

        coroutineScope {
            while (queue.isNotEmpty()) {
                ensureActive()
                val folder = queue.removeFirst()

                val entries = try {
                    SmbFileClient.listFiles(applicationContext, folder)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("ThumbnailPrepare", "Could not list $folder", e)
                    failed.incrementAndGet()
                    continue
                }
                entries.filter { it.isDirectory }.forEach { queue.add(it.url) }

                val media = entries.filter { !it.isDirectory && it.isMedia }
                if (media.isEmpty()) continue

                val smb = SmbPath.parse(folder) ?: continue
                val cachedNames = cachedNamesByShare.getOrPut("${smb.host}/${smb.share}") {
                    SmbThumbnailCache.listCachedNames(applicationContext, folder)
                }

                for (file in media) {
                    ensureActive()
                    permits.acquire()
                    launch(Dispatchers.IO) {
                        try {
                            when (SmbThumbnailCache.prepare(applicationContext, file.url, cachedNames)) {
                                SmbThumbnailCache.PrepareResult.CREATED -> created.incrementAndGet()
                                SmbThumbnailCache.PrepareResult.ALREADY_THERE -> alreadyThere.incrementAndGet()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("ThumbnailPrepare", "No thumbnail for ${file.url}", e)
                            failed.incrementAndGet()
                        } finally {
                            permits.release()
                        }
                        val total = checked.incrementAndGet()
                        if (total % 5 == 0) updateNotification(total, created.get())
                    }
                }
            }
        }
        return Triple(created.get(), alreadyThere.get(), failed.get())
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.thumb_prepare_title),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun progressNotification(checked: Int, created: Int): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ThumbnailPrepareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(getString(R.string.thumb_prepare_title))
            .setContentText(
                if (checked == 0) label else getString(R.string.thumb_prepare_progress, checked, created)
            )
            .setSubText(if (checked == 0) null else label)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.cancel), stopIntent)
            .build()
    }

    private fun updateNotification(checked: Int, created: Int) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, progressNotification(checked, created))
        } catch (e: SecurityException) {
            // Notifications are not allowed: the work continues without them.
        }
    }

    private fun notifyDone(created: Int, alreadyThere: Int, failed: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(getString(R.string.thumb_prepare_title))
            .setContentText(getString(R.string.thumb_prepare_done, created, alreadyThere, failed))
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

/** Starts [ThumbnailPrepareService] from a screen, after a confirmation. */
object ThumbnailPreparer {

    fun start(activity: AppCompatActivity, folderUrl: String, label: String) {
        if (ThumbnailPrepareService.isRunning) {
            Toast.makeText(activity, R.string.thumb_prepare_running, Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.thumb_prepare_menu)
            .setMessage(activity.getString(R.string.thumb_prepare_confirm, label))
            .setPositiveButton(android.R.string.ok) { _, _ -> requestNotificationsThenStart(activity, folderUrl, label) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestNotificationsThenStart(activity: AppCompatActivity, folderUrl: String, label: String) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

        if (!needsPermission) {
            begin(activity, folderUrl, label)
            return
        }

        // Without the permission the work still runs, only the progress notification is hidden.
        lateinit var launcher: ActivityResultLauncher<String>
        launcher = activity.activityResultRegistry.register(
            "notifications_${System.nanoTime()}",
            ActivityResultContracts.RequestPermission()
        ) { _ ->
            launcher.unregister()
            begin(activity, folderUrl, label)
        }
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun begin(activity: AppCompatActivity, folderUrl: String, label: String) {
        ThumbnailPrepareService.start(activity.applicationContext, folderUrl, label)
        Toast.makeText(activity, R.string.thumb_prepare_started, Toast.LENGTH_LONG).show()
    }
}
