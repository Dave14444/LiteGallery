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
import kotlinx.coroutines.async
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
 *
 * It also takes single files (e.g. pictures that were just backed up to the share). Files
 * handed in while a run is going are added to it.
 */
class ThumbnailPrepareService : Service() {

    companion object {
        private const val CHANNEL_ID = "thumbnail_prepare"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "org.iurl.litegallery.action.STOP_PREPARE_THUMBNAILS"
        private const val EXTRA_FOLDER_URL = "folder_url"
        private const val EXTRA_FILE_URLS = "file_urls"
        private const val EXTRA_LABEL = "label"

        /** Pictures processed at the same time (see also SmbThumbnailCache.MAX_PARALLEL_CREATIONS). */
        private const val PARALLEL_FILES = 3

        /** Longest time for one file (a big video over a slow VPN can take a while). */
        private const val FILE_TIMEOUT_MS = 3L * 60L * 1000L

        /** Files that took too long are named in the final notification, at most this many. */
        private const val MAX_NAMED_SLOW_FILES = 5

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

        /**
         * Prepares the thumbnails of [fileUrls] (pictures and videos on a share). If a run is
         * going already, the files are added to it. Throws if Android does not allow starting
         * the service right now (e.g. while the app is in the background).
         */
        fun startForFiles(context: Context, fileUrls: List<String>, label: String) {
            if (fileUrls.isEmpty()) return
            val intent = Intent(context, ThumbnailPrepareService::class.java)
                .putStringArrayListExtra(EXTRA_FILE_URLS, ArrayList(fileUrls))
                .putExtra(EXTRA_LABEL, label)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops a running preparation (same as "Cancel" in its notification). */
        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, ThumbnailPrepareService::class.java).setAction(ACTION_STOP)
                )
            } catch (e: Exception) {
                android.util.Log.w("ThumbnailPrepare", "Could not stop", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs the work for single files, so the run never has to wait for one that hangs. */
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var label = ""

    /** Set by "Cancel": the notification is gone and late results must not bring it back. */
    @Volatile
    private var stopping = false

    // Files handed in that still need their thumbnails, and whether a run is going (both
    // guarded by [lock]): a run takes along what arrives while it is going.
    private val lock = Any()
    private val pendingFiles = ArrayDeque<String>()
    private var running = false
    private var runId = 0

    // The last numbers shown, so the notification keeps them when more files are handed in.
    @Volatile
    private var shownChecked = 0
    @Volatile
    private var shownCreated = 0

    private fun takePendingFiles(): List<String> = synchronized(lock) {
        val files = pendingFiles.toList()
        pendingFiles.clear()
        files
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        if (intent?.action == ACTION_STOP) {
            stopNow()
            return START_NOT_STICKY
        }

        val folderUrl = intent?.getStringExtra(EXTRA_FOLDER_URL)
        val files = intent?.getStringArrayListExtra(EXTRA_FILE_URLS)
        val newLabel = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        val newRun: Int? = synchronized(lock) {
            if (files != null) pendingFiles.addAll(files)
            when {
                // The run that is going takes the files along. A second folder is not started
                // while one runs (ThumbnailPreparer offers to stop the first one instead).
                running -> null
                folderUrl == null && pendingFiles.isEmpty() -> null
                else -> {
                    running = true
                    ++runId
                }
            }
        }
        val startRun = newRun != null
        if (startRun) {
            label = newLabel
            shownChecked = 0
            shownCreated = 0
        }

        // Must be called promptly after startForegroundService().
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            progressNotification(shownChecked, shownCreated),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (!startRun) {
            if (!synchronized(lock) { running }) stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        stopping = false
        job = scope.launch {
            var summary: Summary? = null
            try {
                summary = withTimeoutOrNull(MAX_RUN_MS) { prepareAll(folderUrl) }
            } catch (e: CancellationException) {
                // Stopped by the user.
            } catch (e: Exception) {
                android.util.Log.e("ThumbnailPrepare", "Preparing thumbnails failed", e)
            } finally {
                // Normally the run ended with nothing left to do (see prepareAll); after a
                // failure or the time limit, files handed in are dropped. If a new run started
                // meanwhile, it owns the service and its notification now.
                val stillCurrent = synchronized(lock) {
                    if (runId == newRun) {
                        running = false
                        pendingFiles.clear()
                        true
                    } else {
                        false
                    }
                }
                // After "Cancel" everything was cleaned up already (see stopNow).
                if (!stopping && stillCurrent) {
                    isRunning = false
                    ServiceCompat.stopForeground(this@ThumbnailPrepareService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    if (summary != null) {
                        notifyDone(summary)
                    } else {
                        NotificationManagerCompat.from(this@ThumbnailPrepareService).cancel(NOTIFICATION_ID)
                    }
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Ends the preparation right away. Pictures that are being read over the network at this
     * moment cannot be interrupted; they finish quietly in the background (a few seconds at most,
     * or longer for a big video), but nothing new is started and the notification is gone.
     */
    private fun stopNow() {
        stopping = true
        isRunning = false
        synchronized(lock) {
            pendingFiles.clear()
            running = false
        }
        job?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        detachedScope.coroutineContext[Job]?.cancel()
        isRunning = false
        super.onDestroy()
    }

    /** Returns (created, alreadyThere, failed). */
    private class Summary(
        val created: Int,
        val alreadyThere: Int,
        val failed: Int,
        /** Files given up because they took too long (see FILE_TIMEOUT_MS). */
        val tooSlow: List<String>
    )

    /** What a run counts while it goes. */
    private class Counters {
        val created = AtomicInteger()
        val alreadyThere = AtomicInteger()
        val failed = AtomicInteger()
        val checked = AtomicInteger()
        val tooSlow: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun summary() = Summary(created.get(), alreadyThere.get(), failed.get(), tooSlow.toList())
    }

    /**
     * Prepares everything below [folderUrl] (if given), then the files handed in, also those
     * that arrive meanwhile. Ends when nothing is left; from then on a new start begins a new run.
     */
    private suspend fun prepareAll(folderUrl: String?): Summary {
        val counters = Counters()
        val cachedNamesByShare = HashMap<String, Set<String>>()

        // Several pictures are processed at the same time: most of the time is spent waiting
        // for the network, so this is much faster than one picture after the other.
        val permits = Semaphore(PARALLEL_FILES)

        var folder = folderUrl
        while (true) {
            coroutineScope {
                folder?.let { prepareFolder(it, counters, permits, cachedNamesByShare) }
                folder = null
                var files = takePendingFiles()
                while (files.isNotEmpty()) {
                    prepareFiles(files, counters, permits, cachedNamesByShare)
                    files = takePendingFiles()
                }
            }
            // All started work is finished here. Files handed in meanwhile: one more round.
            val done = synchronized(lock) {
                if (pendingFiles.isEmpty()) {
                    running = false
                    true
                } else {
                    false
                }
            }
            if (done) break
        }
        return counters.summary()
    }

    /** All pictures and videos in [folderUrl] and its sub folders. */
    private suspend fun CoroutineScope.prepareFolder(
        folderUrl: String,
        counters: Counters,
        permits: Semaphore,
        cachedNamesByShare: HashMap<String, Set<String>>
    ) {
        val queue = ArrayDeque<String>()
        queue.add(folderUrl)
        while (queue.isNotEmpty()) {
            ensureActive()
            val folder = queue.removeFirst()

            val entries = try {
                SmbFileClient.listFiles(applicationContext, folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ThumbnailPrepare", "Could not list $folder", e)
                counters.failed.incrementAndGet()
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
                prepareFile(file.url, file.name, cachedNames, counters, permits)
            }
        }
    }

    /** Single files, e.g. pictures that were just backed up to the share. */
    private suspend fun CoroutineScope.prepareFiles(
        fileUrls: List<String>,
        counters: Counters,
        permits: Semaphore,
        cachedNamesByShare: HashMap<String, Set<String>>
    ) {
        // Reading each folder once tells size and date of its new files: the name of a
        // thumbnail is made from them.
        for ((folder, urls) in fileUrls.distinct().groupBy { it.substringBeforeLast('/') }) {
            ensureActive()
            val listed = try {
                SmbFileClient.listFiles(applicationContext, folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ThumbnailPrepare", "Could not list $folder", e)
                counters.failed.addAndGet(urls.size)
                continue
            }
            val media = listed.filter { !it.isDirectory && it.isMedia }.associateBy { it.url }

            val smb = SmbPath.parse(folder) ?: continue
            val cachedNames = cachedNamesByShare.getOrPut("${smb.host}/${smb.share}") {
                SmbThumbnailCache.listCachedNames(applicationContext, folder)
            }
            for (url in urls) {
                // Gone meanwhile (moved or deleted again): nothing to do.
                val file = media[url] ?: continue
                prepareFile(file.url, file.name, cachedNames, counters, permits)
            }
        }
    }

    /** Starts making the thumbnail of one file; waits only while [PARALLEL_FILES] are being made. */
    private suspend fun CoroutineScope.prepareFile(
        url: String,
        name: String,
        cachedNames: Set<String>,
        counters: Counters,
        permits: Semaphore
    ) {
        ensureActive()
        permits.acquire()
        launch(Dispatchers.IO) {
            try {
                // A file that hangs must not stop the whole run. Reading a video
                // frame can hang inside Android's video decoder, where it cannot be
                // interrupted: the work runs on its own and is only waited for a
                // limited time. If it takes too long, the run goes on without it.
                val work = detachedScope.async {
                    SmbThumbnailCache.prepare(applicationContext, url, cachedNames)
                }
                val result = try {
                    withTimeoutOrNull(FILE_TIMEOUT_MS) { work.await() }
                } catch (e: CancellationException) {
                    work.cancel()
                    throw e
                }
                when (result) {
                    SmbThumbnailCache.PrepareResult.CREATED -> counters.created.incrementAndGet()
                    SmbThumbnailCache.PrepareResult.ALREADY_THERE -> counters.alreadyThere.incrementAndGet()
                    null -> {
                        android.util.Log.w("ThumbnailPrepare", "Gave up on $url: took too long")
                        work.cancel()
                        counters.tooSlow.add(name)
                        counters.failed.incrementAndGet()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ThumbnailPrepare", "No thumbnail for $url", e)
                counters.failed.incrementAndGet()
            } finally {
                permits.release()
            }
            val total = counters.checked.incrementAndGet()
            if (total % 5 == 0) updateNotification(total, counters.created.get())
        }
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
        shownChecked = checked
        shownCreated = created
        if (stopping) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, progressNotification(checked, created))
        } catch (e: SecurityException) {
            // Notifications are not allowed: the work continues without them.
        }
    }

    private fun notifyDone(summary: Summary) {
        val text = getString(R.string.thumb_prepare_done, summary.created, summary.alreadyThere, summary.failed)
        val details = if (summary.tooSlow.isEmpty()) {
            text
        } else {
            val names = summary.tooSlow.take(MAX_NAMED_SLOW_FILES).joinToString(", ") +
                if (summary.tooSlow.size > MAX_NAMED_SLOW_FILES) ", …" else ""
            text + "\n" + getString(R.string.thumb_prepare_too_slow, names)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(getString(R.string.thumb_prepare_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
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
            // Already running: offer to stop it (also possible from its notification).
            AlertDialog.Builder(activity)
                .setTitle(R.string.thumb_prepare_menu)
                .setMessage(R.string.thumb_prepare_running)
                .setPositiveButton(R.string.thumb_prepare_stop) { _, _ ->
                    ThumbnailPrepareService.stop(activity.applicationContext)
                    Toast.makeText(activity, R.string.thumb_prepare_stopped, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.thumb_prepare_continue, null)
                .show()
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
