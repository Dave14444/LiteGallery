package org.iurl.litegallery

import android.text.format.Formatter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date

/**
 * Plain list of folders and files with their names only (no thumbnails). Used for folders with
 * camera RAW files, which are not shown as pictures but can be opened in another app.
 */
class RemoteListAdapter(
    private val onItemClick: (RemoteFileInfo) -> Unit
) : ListAdapter<RemoteFileInfo, RemoteListAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(android.R.id.text1)
        private val subtitle = view.findViewById<TextView>(android.R.id.text2)

        init {
            val value = TypedValue()
            if (view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
                view.setBackgroundResource(value.resourceId)
            }
        }

        fun bind(item: RemoteFileInfo) {
            val context = itemView.context
            if (item.isDirectory) {
                title.text = item.name + "/"
                subtitle.visibility = View.GONE
            } else {
                title.text = item.name
                subtitle.visibility = View.VISIBLE
                subtitle.text = Formatter.formatShortFileSize(context, item.size) + " · " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.lastModified))
            }
            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<RemoteFileInfo>() {
        override fun areItemsTheSame(oldItem: RemoteFileInfo, newItem: RemoteFileInfo): Boolean =
            oldItem.url == newItem.url

        override fun areContentsTheSame(oldItem: RemoteFileInfo, newItem: RemoteFileInfo): Boolean =
            oldItem == newItem
    }
}
