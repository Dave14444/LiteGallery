package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Folder browser for choosing where files are copied or moved to: the phone's storage or any
 * destination offered by a [MediaSource] (e.g. the saved network shares). New folders can be
 * created on the spot.
 */
object DestinationPicker {

    private const val PREFS = "file_operations"
    private const val KEY_LAST_DESTINATION = "last_destination"

    /** [path] is the folder this row leads to; null means the list of storage locations. */
    private data class Row(val label: String, val path: String?)

    fun show(
        activity: AppCompatActivity,
        mode: FileTransfer.Mode,
        onPicked: (String) -> Unit
    ) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val phoneRoot = Environment.getExternalStorageDirectory().absolutePath

        val listView = ListView(activity)
        val rowAdapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        listView.adapter = rowAdapter
        var rows: List<Row> = emptyList()
        var currentPath: String? = null

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.file_op_choose_destination)
            .setView(listView)
            .setPositiveButton(
                if (mode == FileTransfer.Mode.COPY) R.string.file_op_copy_here else R.string.file_op_move_here,
                null
            )
            .setNeutralButton(R.string.file_op_new_folder, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        fun showRows(newRows: List<Row>) {
            rows = newRows
            rowAdapter.clear()
            rowAdapter.addAll(newRows.map { it.label })
            rowAdapter.notifyDataSetChanged()
        }

        fun titleFor(path: String?): String = when {
            path == null -> activity.getString(R.string.file_op_choose_destination)
            path.contains("://") -> path.substringAfter("://")
            path.startsWith(phoneRoot) ->
                activity.getString(R.string.file_op_phone_storage) + path.removePrefix(phoneRoot)
            else -> path
        }

        // path == null: show the list of storage locations
        fun load(path: String?) {
            currentPath = path
            dialog.setTitle(titleFor(path))
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = path != null
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = path != null

            activity.lifecycleScope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        buildRows(activity.applicationContext, path, phoneRoot)
                    }
                    showRows(loaded)
                } catch (e: Exception) {
                    android.util.Log.e("DestinationPicker", "Could not open $path", e)
                    Toast.makeText(activity, R.string.file_op_folder_failed, Toast.LENGTH_LONG).show()
                    // Always offer a way out, e.g. when the last used folder is not reachable.
                    showRows(listOf(Row("..", null)))
                }
            }
        }

        fun createFolder() {
            val parent = currentPath ?: return
            val input = EditText(activity).apply {
                setSingleLine()
                setHint(R.string.file_op_new_folder_hint)
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.file_op_new_folder)
                .setView(input)
                .setPositiveButton(R.string.file_op_create) { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty() || name.contains('/') || name.contains('\\')) {
                        Toast.makeText(activity, R.string.file_op_folder_create_failed, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    activity.lifecycleScope.launch {
                        val created = withContext(Dispatchers.IO) {
                            createFolderIn(activity.applicationContext, parent, name)
                        }
                        if (created != null) {
                            load(created)
                        } else {
                            Toast.makeText(activity, R.string.file_op_folder_create_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val path = currentPath
            if (path != null) {
                prefs.edit().putString(KEY_LAST_DESTINATION, path).apply()
                dialog.dismiss()
                onPicked(path)
            }
        }
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            createFolder()
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val target = rows.getOrNull(position)?.path
            // Browsing the phone's storage needs all-files access.
            if (target != null && FileTransfer.isLocalPath(target) && !StorageAccess.ensure(activity)) {
                return@setOnItemClickListener
            }
            load(target)
        }

        val last = prefs.getString(KEY_LAST_DESTINATION, null)
        val usable = last != null && (
            !FileTransfer.isLocalPath(last) ||
                (StorageAccess.hasAllFilesAccess() && File(last).isDirectory)
            )
        load(if (usable) last else null)
    }

    private fun buildRows(context: Context, path: String?, phoneRoot: String): List<Row> {
        if (path == null) {
            val roots = mutableListOf(Row(context.getString(R.string.file_op_phone_storage), phoneRoot))
            MediaSourceRegistry.all().forEach { source ->
                source.destinationRoots(context).forEach { roots.add(Row(it.name, it.path)) }
            }
            return roots
        }

        val source = MediaSourceRegistry.forPath(path)
        val parent: String?
        val folders: List<FolderEntry>
        if (source == null) {
            parent = if (path == phoneRoot) null else File(path).parent
            folders = File(path).listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
                ?.map { FolderEntry(it.name, it.absolutePath) }
                ?: emptyList()
        } else {
            parent = source.parentFolder(path)
            folders = source.listSubFolders(context, path)
        }

        return listOf(Row("..", parent)) +
            folders.sortedBy { it.name.lowercase() }.map { Row(it.name + "/", it.path) }
    }

    /** Creates folder [name] in [parent] and returns its path, or null. */
    private fun createFolderIn(context: Context, parent: String, name: String): String? {
        val source = MediaSourceRegistry.forPath(parent)
        if (source != null) return source.createFolder(context, parent, name)

        val folder = File(parent, name)
        return if (folder.isDirectory || folder.mkdirs()) folder.absolutePath else null
    }
}
