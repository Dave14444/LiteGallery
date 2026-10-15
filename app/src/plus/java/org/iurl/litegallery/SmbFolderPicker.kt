package org.iurl.litegallery

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Simple folder browser for choosing a destination on any saved SMB share.
 * ".." at a share root leads to the list of saved shares.
 */
object SmbFolderPicker {

    private data class Row(val label: String, val target: String?)

    /**
     * Shows the picker, starting in [startUrl] (or at the list of saved shares if null/invalid).
     * [onPicked] is called with the chosen folder URL.
     */
    fun show(
        activity: AppCompatActivity,
        startUrl: String?,
        positiveLabelRes: Int,
        onPicked: (String) -> Unit
    ) {
        val listView = ListView(activity)
        val rowAdapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        listView.adapter = rowAdapter
        var rows: List<Row> = emptyList()
        var pickerUrl: String? = null

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.smb_choose_destination)
            .setView(listView)
            .setPositiveButton(positiveLabelRes, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        fun showRows(newRows: List<Row>) {
            rows = newRows
            rowAdapter.clear()
            rowAdapter.addAll(newRows.map { it.label })
            rowAdapter.notifyDataSetChanged()
        }

        // url == null: list of saved shares
        fun load(url: String?) {
            pickerUrl = url
            dialog.setTitle(url?.removePrefix("smb://") ?: activity.getString(R.string.smb_choose_server))
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled =
                url != null && SmbPath.parse(url) != null
            activity.lifecycleScope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        if (url == null) {
                            NetworkConfigStore.getAllServers(activity.applicationContext)
                                .filter { server -> !server.isNfs && server.share.isNotBlank() }
                                .map { server ->
                                    Row(server.displayName.ifBlank { server.host }, server.rootUrl())
                                }
                        } else {
                            val parent = SmbPath.parse(url)?.let {
                                if (it.path.isBlank()) null else it.parentFolderPath
                            }
                            val folders = SmbFileClient.listFiles(activity.applicationContext, url)
                                .filter { it.isDirectory }
                                .sortedBy { it.name.lowercase() }
                                .map { Row(it.name + "/", it.url) }
                            listOf(Row("..", parent)) + folders
                        }
                    }
                    showRows(loaded)
                } catch (e: Exception) {
                    android.util.Log.e("SmbFolderPicker", "Could not list $url", e)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.smb_connection_failed, e.message ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                    // Offer a way out, e.g. when the last used folder is no longer reachable.
                    showRows(listOf(Row("..", null)))
                }
            }
        }

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val url = pickerUrl
            if (url != null && SmbPath.parse(url) != null) {
                dialog.dismiss()
                onPicked(url)
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            rows.getOrNull(position)?.let { load(it.target) }
        }
        load(startUrl?.takeIf { SmbPath.isSmb(it) })
    }
}
