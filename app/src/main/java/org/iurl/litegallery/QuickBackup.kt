package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Back up now": for each remembered pair of a source folder (on the phone, e.g. the camera
 * folder) and a destination (e.g. a folder on a network share), all pictures and videos are
 * moved except the newest one, which stays. The originals are deleted after they were copied
 * successfully. Any number of pairs can be kept; each backup run asks which of them to use.
 */
object QuickBackup {

    private const val PREFS = "quick_backup"
    private const val KEY_JOBS = "jobs"

    // Keys of the first version, which remembered a single pair.
    private const val LEGACY_KEY_SOURCE = "source_folder"
    private const val LEGACY_KEY_DESTINATION = "destination_folder"

    private const val PICKER_KEY_SOURCE = "quick_backup_pick_source"
    private const val PICKER_KEY_DESTINATION = "quick_backup_pick_destination"

    class Job(val source: String, val destination: String)

    fun start(activity: AppCompatActivity, onFinished: () -> Unit) {
        val jobs = loadJobs(activity)
        if (jobs.isEmpty()) {
            addJob(activity) { start(activity, onFinished) }
        } else {
            showOverview(activity, jobs, onFinished)
        }
    }

    // ---------------------------------------------------------------- overview and run

    /** Lists the pairs with the number of files each one would move; the chosen ones are run. */
    private fun showOverview(activity: AppCompatActivity, jobs: List<Job>, onFinished: () -> Unit) {
        val counts = jobs.map { filesToMove(it.source)?.size }
        val labels = jobs.mapIndexed { index, job ->
            val count = counts[index]
            val suffix = if (count == null) {
                activity.getString(R.string.backup_pair_unreadable)
            } else {
                activity.getString(R.string.backup_pair_count, count)
            }
            activity.getString(
                R.string.backup_pair_label,
                displayPath(activity, job.source),
                displayPath(activity, job.destination)
            ) + " " + suffix
        }.toTypedArray()
        val checked = BooleanArray(jobs.size) { (counts[it] ?: 0) > 0 }

        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.backup_title) { _, _ ->
                val chosen = jobs.filterIndexed { index, _ -> checked[index] }
                if (chosen.isEmpty()) {
                    Toast.makeText(activity, R.string.backup_nothing_chosen, Toast.LENGTH_LONG).show()
                } else {
                    moveFiles(activity, chosen, onFinished)
                }
            }
            .setNeutralButton(R.string.backup_manage) { _, _ -> manage(activity, onFinished) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun moveFiles(activity: AppCompatActivity, jobs: List<Job>, onFinished: () -> Unit) {
        // Moving deletes the files on the phone afterwards.
        if (!StorageAccess.ensure(activity)) return

        val plan = jobs.map { job -> job to (filesToMove(job.source) ?: emptyList()) }
        val total = plan.sumOf { it.second.size }
        if (total == 0) {
            Toast.makeText(activity, R.string.backup_nothing_to_move, Toast.LENGTH_LONG).show()
            return
        }

        FileOperationDialogs.runWithProgress(
            activity = activity,
            titleRes = R.string.file_op_moving,
            total = total,
            work = { onProgress ->
                var done = 0
                var failed = 0
                var finishedFiles = 0
                val created = mutableListOf<String>()
                val removed = mutableListOf<String>()

                plan.forEach { (job, files) ->
                    if (files.isNotEmpty()) {
                        val base = finishedFiles
                        val result = FileTransfer.transfer(
                            activity.applicationContext,
                            files.map { it.absolutePath },
                            job.destination,
                            FileTransfer.Mode.MOVE,
                            onProgress = { filesDone, _ -> onProgress(base + filesDone, total) }
                        )
                        done += result.done
                        failed += result.failed
                        created.addAll(result.createdPaths)
                        removed.addAll(result.removedPaths)
                        finishedFiles += files.size
                    }
                }
                FileTransfer.Result(done, failed, created, removed)
            },
            onDone = { result ->
                jobs.forEach {
                    FolderMediaRepository.invalidate(it.source)
                    FolderMediaRepository.invalidate(it.destination)
                }

                // The gallery's own index must forget the files that left the phone.
                val scanner = MediaScanner(activity.applicationContext)
                activity.lifecycleScope.launch {
                    result.removedPaths
                        .filter { FileTransfer.isLocalPath(it) }
                        .forEach { scanner.removeIndexedMediaPath(it) }
                    onFinished()
                }
            }
        )
    }

    // ---------------------------------------------------------------- managing the pairs

    /** Shows the remembered pairs; tapping one offers to remove it, the last entry adds a new one. */
    private fun manage(activity: AppCompatActivity, onFinished: () -> Unit) {
        val jobs = loadJobs(activity)
        val labels = jobs.map { job ->
            activity.getString(
                R.string.backup_pair_label,
                displayPath(activity, job.source),
                displayPath(activity, job.destination)
            )
        } + activity.getString(R.string.backup_add_pair)

        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_manage_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == jobs.size) {
                    addJob(activity) { start(activity, onFinished) }
                } else {
                    confirmRemove(activity, jobs, which, onFinished)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(
        activity: AppCompatActivity,
        jobs: List<Job>,
        index: Int,
        onFinished: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setMessage(R.string.backup_remove_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveJobs(activity, jobs.filterIndexed { i, _ -> i != index })
                start(activity, onFinished)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> manage(activity, onFinished) }
            .show()
    }

    /** Asks for the source folder (phone), then for the destination, and remembers the pair. */
    private fun addJob(activity: AppCompatActivity, onAdded: () -> Unit) {
        // Browsing the phone's folders and moving files out of them needs all-files access.
        if (!StorageAccess.ensure(activity)) return

        DestinationPicker.pick(
            activity,
            R.string.backup_choose_source,
            R.string.backup_use_folder,
            phoneOnly = true,
            rememberKey = PICKER_KEY_SOURCE
        ) { source ->
            DestinationPicker.pick(
                activity,
                R.string.file_op_choose_destination,
                R.string.backup_use_folder,
                phoneOnly = false,
                rememberKey = PICKER_KEY_DESTINATION
            ) { destination ->
                val jobs = loadJobs(activity)
                when {
                    destination.trimEnd('/') == source.trimEnd('/') -> {
                        Toast.makeText(activity, R.string.backup_same_folder, Toast.LENGTH_LONG).show()
                        addJob(activity, onAdded)
                    }
                    jobs.any { it.source == source && it.destination == destination } -> {
                        Toast.makeText(activity, R.string.backup_pair_exists, Toast.LENGTH_LONG).show()
                        onAdded()
                    }
                    else -> {
                        saveJobs(activity, jobs + Job(source, destination))
                        onAdded()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- storage

    private fun loadJobs(context: Context): List<Job> {
        val prefs = prefs(context)

        val raw = prefs.getString(KEY_JOBS, null)
        if (raw != null) {
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).map { index ->
                    val entry = array.getJSONObject(index)
                    Job(entry.getString("source"), entry.getString("destination"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        // Take over the single pair of the first version.
        val source = prefs.getString(LEGACY_KEY_SOURCE, null)
        val destination = prefs.getString(LEGACY_KEY_DESTINATION, null)
        if (source != null && destination != null) {
            val migrated = listOf(Job(source, destination))
            saveJobs(context, migrated)
            return migrated
        }
        return emptyList()
    }

    private fun saveJobs(context: Context, jobs: List<Job>) {
        val array = JSONArray()
        jobs.forEach { job ->
            array.put(JSONObject().put("source", job.source).put("destination", job.destination))
        }
        prefs(context).edit()
            .putString(KEY_JOBS, array.toString())
            .remove(LEGACY_KEY_SOURCE)
            .remove(LEGACY_KEY_DESTINATION)
            .apply()
    }

    // ---------------------------------------------------------------- files

    /**
     * The pictures and videos directly in [source], oldest first, without the newest one.
     * Null if the folder cannot be read.
     */
    private fun filesToMove(source: String): List<File>? {
        val files = File(source).listFiles { file -> file.isFile && isMedia(file) } ?: return null
        val sorted = files.sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
        return if (sorted.size <= 1) emptyList() else sorted.dropLast(1)
    }

    private fun isMedia(file: File): Boolean {
        // Hidden files include pictures that are still being written and trashed files.
        if (file.name.startsWith(".")) return false
        val mime = MediaMimeTypes.fromExtension(file.extension)
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    private fun displayPath(context: Context, path: String): String {
        if (path.contains("://")) return path.substringAfter("://")
        val phoneRoot = Environment.getExternalStorageDirectory().absolutePath
        return if (path.startsWith(phoneRoot)) {
            context.getString(R.string.file_op_phone_storage) + path.removePrefix(phoneRoot)
        } else {
            path
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
