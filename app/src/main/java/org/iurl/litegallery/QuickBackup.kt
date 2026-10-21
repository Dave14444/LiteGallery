package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Back up now": moves all pictures and videos from a chosen folder on the phone (e.g. the
 * camera folder) to a chosen destination (e.g. a folder on a network share) - except the
 * newest one, which stays. The originals are deleted after they were copied successfully.
 * Source and destination are chosen once and remembered.
 */
object QuickBackup {

    private const val PREFS = "quick_backup"
    private const val KEY_SOURCE = "source_folder"
    private const val KEY_DESTINATION = "destination_folder"
    private const val PICKER_KEY_SOURCE = "quick_backup_pick_source"
    private const val PICKER_KEY_DESTINATION = "quick_backup_pick_destination"

    fun start(activity: AppCompatActivity, onFinished: () -> Unit) {
        val prefs = prefs(activity)
        val source = prefs.getString(KEY_SOURCE, null)
        val destination = prefs.getString(KEY_DESTINATION, null)

        if (source == null || destination == null) {
            configure(activity) { start(activity, onFinished) }
        } else {
            confirm(activity, source, destination, onFinished)
        }
    }

    /** Asks for the source folder (phone), then for the destination, and remembers both. */
    private fun configure(activity: AppCompatActivity, onConfigured: () -> Unit) {
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
                if (destination.trimEnd('/') == source.trimEnd('/')) {
                    Toast.makeText(activity, R.string.backup_same_folder, Toast.LENGTH_LONG).show()
                    configure(activity, onConfigured)
                } else {
                    prefs(activity).edit()
                        .putString(KEY_SOURCE, source)
                        .putString(KEY_DESTINATION, destination)
                        .apply()
                    onConfigured()
                }
            }
        }
    }

    private fun confirm(
        activity: AppCompatActivity,
        source: String,
        destination: String,
        onFinished: () -> Unit
    ) {
        val files = filesToMove(source)
        if (files == null) {
            Toast.makeText(activity, R.string.backup_source_missing, Toast.LENGTH_LONG).show()
            configure(activity) { start(activity, onFinished) }
            return
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.backup_title)
            .setNeutralButton(R.string.backup_change_folders) { _, _ ->
                configure(activity) { start(activity, onFinished) }
            }
        if (files.isEmpty()) {
            builder
                .setMessage(activity.getString(R.string.backup_nothing, displayPath(activity, source)))
                .setPositiveButton(android.R.string.ok, null)
        } else {
            builder
                .setMessage(
                    activity.getString(
                        R.string.backup_confirm_message,
                        displayPath(activity, source),
                        displayPath(activity, destination),
                        files.size
                    )
                )
                .setPositiveButton(R.string.backup_title) { _, _ ->
                    moveFiles(activity, source, destination, files, onFinished)
                }
                .setNegativeButton(R.string.cancel, null)
        }
        builder.show()
    }

    private fun moveFiles(
        activity: AppCompatActivity,
        source: String,
        destination: String,
        files: List<File>,
        onFinished: () -> Unit
    ) {
        // Moving deletes the files on the phone afterwards.
        if (!StorageAccess.ensure(activity)) return

        val paths = files.map { it.absolutePath }
        FileOperationDialogs.runWithProgress(
            activity = activity,
            titleRes = R.string.file_op_moving,
            total = paths.size,
            work = { onProgress ->
                FileTransfer.transfer(
                    activity.applicationContext,
                    paths,
                    destination,
                    FileTransfer.Mode.MOVE,
                    onProgress
                )
            },
            onDone = { result ->
                FolderMediaRepository.invalidate(source)
                FolderMediaRepository.invalidate(destination)

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
