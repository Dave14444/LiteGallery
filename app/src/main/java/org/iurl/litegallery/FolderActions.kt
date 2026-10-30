package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.media.MediaScannerConnection
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The long-press menu of a folder: rename, copy, move or delete the whole folder with everything
 * in it (see [FolderTransfer]), plus entries of the screen that shows it (e.g. hide on the home screen).
 */
object FolderActions {

    /** An entry the calling screen adds to the menu. */
    class Extra(val label: String, val run: () -> Unit)

    // The same choice as when deleting pictures (folder screen and viewer).
    private const val DELETE_PREFS = "delete_prefs"
    private const val DELETE_MOVE_TO_TRASH_KEY = "delete_move_to_trash"

    /** How long the gallery waits for the phone's media index to know new files. */
    private const val SCAN_WAIT_MS = 10_000L

    /**
     * Shows the menu for the folder [path]. Copy, move and delete appear where they are possible
     * (not for a whole network share, for instance). [onChanged] runs after an operation, once
     * the gallery knows about the changes. Returns false if there was nothing to offer.
     */
    fun showMenu(
        activity: AppCompatActivity,
        path: String,
        name: String,
        extras: List<Extra> = emptyList(),
        onChanged: () -> Unit
    ): Boolean {
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        if (FolderTransfer.canHandle(path)) {
            entries.add(activity.getString(R.string.folder_op_rename) to {
                startRename(activity, path, onChanged)
            })
            entries.add(activity.getString(R.string.folder_op_copy) to {
                startTransfer(activity, path, FileTransfer.Mode.COPY, onChanged)
            })
            entries.add(activity.getString(R.string.folder_op_move) to {
                startTransfer(activity, path, FileTransfer.Mode.MOVE, onChanged)
            })
            entries.add(activity.getString(R.string.folder_op_delete) to {
                startDelete(activity, path, onChanged)
            })
        }
        extras.forEach { entries.add(it.label to it.run) }
        if (entries.isEmpty()) return false

        AlertDialog.Builder(activity)
            .setTitle(name)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .showThemed()
        return true
    }

    // ---------------------------------------------------------------- copy / move

    private fun startTransfer(
        activity: AppCompatActivity,
        path: String,
        mode: FileTransfer.Mode,
        onChanged: () -> Unit
    ) {
        // Folders on the phone are read and changed directly: that needs all-files access.
        if (FileTransfer.isLocalPath(path) && !StorageAccess.ensure(activity)) return
        val folder = path.trimEnd('/')

        DestinationPicker.show(activity, mode) { picked ->
            if (FileTransfer.isLocalPath(picked) && !StorageAccess.ensure(activity)) return@show
            val destParent = picked.trimEnd('/')
            val currentParent = FolderTransfer.parentOf(folder)?.trimEnd('/')
            when {
                destParent == folder || destParent.startsWith("$folder/") ->
                    toast(activity, R.string.folder_op_into_itself)
                mode == FileTransfer.Mode.MOVE && destParent == currentParent ->
                    toast(activity, R.string.folder_op_already_there)
                else -> readFolder(activity, folder, destParent) { tree, taken ->
                    val name = FolderTransfer.nameOf(folder)
                    when {
                        name.lowercase() !in taken ->
                            runTransfer(activity, tree, destParent, name, false, mode, onChanged)
                        // A copy next to the original: it gets a new name, merging would mix it into itself.
                        destParent == currentParent ->
                            runTransfer(
                                activity, tree, destParent, FolderTransfer.freeName(name, taken),
                                false, mode, onChanged
                            )
                        else -> askForName(activity, name, taken) { merge, targetName ->
                            runTransfer(activity, tree, destParent, targetName, merge, mode, onChanged)
                        }
                    }
                }
            }
        }
    }

    /** The destination has a folder of that name already: merge into it or use a new name? */
    private fun askForName(
        activity: AppCompatActivity,
        name: String,
        taken: Set<String>,
        onChoice: (merge: Boolean, targetName: String) -> Unit
    ) {
        val newName = FolderTransfer.freeName(name, taken)
        AlertDialog.Builder(activity)
            .setTitle(R.string.folder_op_exists_title)
            .setMessage(activity.getString(R.string.folder_op_exists_message, name, newName))
            .setPositiveButton(R.string.folder_op_merge) { _, _ -> onChoice(true, name) }
            .setNeutralButton(R.string.folder_op_new_name) { _, _ -> onChoice(false, newName) }
            .setNegativeButton(R.string.cancel, null)
            .showThemed()
    }

    private fun runTransfer(
        activity: AppCompatActivity,
        tree: FolderTransfer.Tree,
        destParent: String,
        targetName: String,
        merge: Boolean,
        mode: FileTransfer.Mode,
        onChanged: () -> Unit
    ) {
        val appContext = activity.applicationContext
        FileOperationDialogs.runWithProgress(
            activity = activity,
            titleRes = if (mode == FileTransfer.Mode.COPY) R.string.file_op_copying else R.string.file_op_moving,
            total = tree.fileCount,
            work = { onProgress ->
                FolderTransfer.transfer(appContext, tree, destParent, targetName, merge, mode, onProgress)
            },
            onDone = { result -> afterChange(activity, result, onChanged) }
        )
    }

    // ---------------------------------------------------------------- rename

    private fun startRename(activity: AppCompatActivity, path: String, onChanged: () -> Unit) {
        if (FileTransfer.isLocalPath(path) && !StorageAccess.ensure(activity)) return
        val folder = path.trimEnd('/')
        val parent = FolderTransfer.parentOf(folder) ?: return
        val oldName = FolderTransfer.nameOf(folder)

        val input = EditText(activity).apply {
            setSingleLine()
            setText(oldName)
            selectAll()
        }
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.folder_op_rename_title)
            .setView(container)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName == oldName -> Unit
                    !FolderTransfer.isValidFolderName(newName) -> toast(activity, R.string.folder_op_invalid_name)
                    else -> readFolder(activity, folder, parent) { tree, taken ->
                        // Also a change of upper and lower case only: on the phone and on a NAS
                        // that is the same name, so the files would be renamed one by one.
                        if (newName.lowercase() in taken) {
                            toast(activity, R.string.folder_op_name_taken)
                        } else {
                            // Renaming is moving into the same folder under the new name.
                            runTransfer(activity, tree, parent, newName, false, FileTransfer.Mode.MOVE, onChanged)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .showThemed()
    }

    // ---------------------------------------------------------------- delete

    private fun startDelete(activity: AppCompatActivity, path: String, onChanged: () -> Unit) {
        if (FileTransfer.isLocalPath(path) && !StorageAccess.ensure(activity)) return
        readFolder(activity, path.trimEnd('/'), null) { tree, _ -> confirmDelete(activity, tree, onChanged) }
    }

    private fun confirmDelete(activity: AppCompatActivity, tree: FolderTransfer.Tree, onChanged: () -> Unit) {
        val source = MediaSourceRegistry.forPath(tree.root)
        val isLocal = source == null
        val canTrash = source?.supportsTrash(tree.root) ?: true
        val name = FolderTransfer.nameOf(tree.root)

        val prefs = activity.getSharedPreferences(DELETE_PREFS, Context.MODE_PRIVATE)
        val view = activity.layoutInflater.inflate(R.layout.dialog_delete_media, null)
        val message = view.findViewById<TextView>(R.id.deleteMessageTextView)
        val trashBox = view.findViewById<CheckBox>(R.id.moveToTrashCheckBox)
        trashBox.isChecked = canTrash && prefs.getBoolean(DELETE_MOVE_TO_TRASH_KEY, true)
        trashBox.visibility = if (canTrash) View.VISIBLE else View.GONE

        val question = if (tree.subFolders.isEmpty()) {
            activity.getString(R.string.folder_op_delete_confirmation, name, tree.fileCount)
        } else {
            activity.getString(
                R.string.folder_op_delete_confirmation_sub, name, tree.fileCount, tree.subFolders.size
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.folder_op_delete_title)
            .setView(view)
            .setPositiveButton(R.string.delete, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            val update = {
                val toTrash = canTrash && trashBox.isChecked
                positive.text = activity.getString(if (toTrash) R.string.move_to_trash else R.string.delete_permanently)
                // On the phone only pictures and videos can go to the trash.
                message.text = if (isLocal && toTrash) {
                    question + "\n\n" + activity.getString(R.string.folder_op_trash_phone_note)
                } else {
                    question
                }
            }
            update()
            trashBox.setOnCheckedChangeListener { _, _ -> update() }

            positive.setOnClickListener {
                val toTrash = canTrash && trashBox.isChecked
                if (canTrash) prefs.edit().putBoolean(DELETE_MOVE_TO_TRASH_KEY, toTrash).apply()
                dialog.dismiss()
                val appContext = activity.applicationContext
                FileOperationDialogs.runWithProgress(
                    activity = activity,
                    titleRes = R.string.file_op_deleting,
                    total = tree.fileCount,
                    work = { onProgress -> FolderTransfer.delete(appContext, tree, toTrash, onProgress) },
                    onDone = { result -> afterChange(activity, result, onChanged) }
                )
            }
        }
        dialog.show()
        ThemeHelper.applyRuntimeCustomColors(dialog)
    }

    // ---------------------------------------------------------------- shared steps

    /**
     * Reads the folder [path] (and the names in [destParent], if given) while a small dialog
     * with a Cancel button is shown, then hands both to [then].
     */
    private fun readFolder(
        activity: AppCompatActivity,
        path: String,
        destParent: String?,
        then: (FolderTransfer.Tree, Set<String>) -> Unit
    ) {
        val appContext = activity.applicationContext
        val stop = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setMessage(R.string.folder_op_reading)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> stop.set(true) }
            .showThemed()

        activity.lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) {
                try {
                    val tree = FolderTransfer.scan(appContext, path) { stop.get() }
                    val taken = destParent?.let { FolderTransfer.namesIn(appContext, it) } ?: emptySet()
                    tree to taken
                } catch (e: Exception) {
                    if (!stop.get()) android.util.Log.w("FolderActions", "Could not read $path", e)
                    null
                }
            }
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (_: Exception) {
            }
            if (stop.get()) return@launch
            if (read == null) {
                toast(activity, R.string.folder_op_read_failed)
                return@launch
            }
            then(read.first, read.second)
        }
    }

    /**
     * After an operation: the gallery forgets what is gone and learns about new files on the
     * phone, then the screen updates itself.
     */
    private fun afterChange(activity: AppCompatActivity, result: FileTransfer.Result, onChanged: () -> Unit) {
        FolderMediaRepository.clear()
        val appContext = activity.applicationContext
        val removedLocal = result.removedPaths.filter { FileTransfer.isLocalPath(it) }
        val createdLocal = result.createdPaths.filter { FileTransfer.isLocalPath(it) }
        activity.lifecycleScope.launch {
            if (removedLocal.isNotEmpty()) MediaScanner(appContext).removeIndexedMediaPaths(removedLocal)
            if (createdLocal.isNotEmpty()) awaitMediaScan(appContext, createdLocal)
            onChanged()
        }
    }

    /** Waits (a few seconds at most) until the phone's media index knows [paths]. */
    private suspend fun awaitMediaScan(context: Context, paths: List<String>) {
        val finished = CompletableDeferred<Unit>()
        val remaining = AtomicInteger(paths.size)
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
            if (remaining.decrementAndGet() == 0) finished.complete(Unit)
        }
        withTimeoutOrNull(SCAN_WAIT_MS) { finished.await() }
    }

    private fun toast(activity: AppCompatActivity, textRes: Int) {
        Toast.makeText(activity, textRes, Toast.LENGTH_LONG).show()
    }

    private fun AlertDialog.Builder.showThemed(): AlertDialog {
        val dialog = create()
        dialog.show()
        ThemeHelper.applyRuntimeCustomColors(dialog)
        return dialog
    }
}
