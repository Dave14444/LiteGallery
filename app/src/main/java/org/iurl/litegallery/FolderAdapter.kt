package org.iurl.litegallery

import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.iurl.litegallery.databinding.ItemFolderBinding
import org.iurl.litegallery.databinding.ItemFolderDetailedBinding
import org.iurl.litegallery.databinding.ItemFolderListBinding
import java.text.NumberFormat

class FolderAdapter(
    private val onFolderClick: (MediaFolder) -> Unit,
    private val onFolderLongClick: (MediaFolder) -> Unit
) : ListAdapter<MediaFolder, FolderAdapter.Holder>(FolderDiffCallback()) {

    /**
     * How the folders are shown: as cards (small ones in the thumbnail view, big ones in the
     * large tile view), or as rows like the pictures in the list and detail views.
     */
    var viewMode: MediaAdapter.ViewMode = MediaAdapter.ViewMode.LARGE_GRID
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun getItemViewType(position: Int): Int = when (viewMode) {
        MediaAdapter.ViewMode.GRID, MediaAdapter.ViewMode.LARGE_GRID -> VIEW_TYPE_CARD
        MediaAdapter.ViewMode.LIST -> VIEW_TYPE_LIST
        MediaAdapter.ViewMode.DETAILED -> VIEW_TYPE_DETAILED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_LIST -> ListHolder(ItemFolderListBinding.inflate(inflater, parent, false))
            VIEW_TYPE_DETAILED -> DetailedHolder(ItemFolderDetailedBinding.inflate(inflater, parent, false))
            else -> CardHolder(ItemFolderBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val folder = getItem(position)
        holder.bind(folder)
        holder.itemView.setOnClickListener { onFolderClick(folder) }
        holder.itemView.setOnLongClickListener {
            onFolderLongClick(folder)
            true
        }
    }

    abstract class Holder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(folder: MediaFolder)
    }

    /** A card with a big cover (the thumbnail and large tile views). */
    inner class CardHolder(private val binding: ItemFolderBinding) : Holder(binding.root) {

        private val nameTextSizePx = binding.folderNameTextView.textSize

        override fun bind(folder: MediaFolder) {
            // Three cards side by side in the thumbnail view: a smaller name.
            val compact = viewMode == MediaAdapter.ViewMode.GRID
            binding.folderNameTextView.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                if (compact) nameTextSizePx * COMPACT_NAME_SCALE else nameTextSizePx
            )
            binding.folderNameTextView.text = folder.name
            bindCount(binding.countChip, folder)
            val meta = shortMeta(folder)
            binding.folderMetaTextView.text = meta
            binding.folderMetaTextView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
            loadCover(binding.thumbnailImageView, folder)
        }
    }

    /** A row with a small cover, name and count (the list view). */
    inner class ListHolder(private val binding: ItemFolderListBinding) : Holder(binding.root) {
        override fun bind(folder: MediaFolder) {
            binding.folderNameTextView.text = folder.name
            bindCount(binding.countChip, folder)
            loadCover(binding.thumbnailImageView, folder)
        }
    }

    /** A row with a bigger cover, the number of pictures, size and date (the detail view). */
    inner class DetailedHolder(private val binding: ItemFolderDetailedBinding) : Holder(binding.root) {
        override fun bind(folder: MediaFolder) {
            val context = binding.root.context
            binding.folderNameTextView.text = folder.name
            showOrHide(
                binding.folderCountTextView,
                if (folder.itemCount >= 0) context.getString(R.string.items_count, folder.itemCount) else ""
            )
            showOrHide(
                binding.folderSizeTextView,
                if (folder.totalSizeBytes > 0L) Formatter.formatShortFileSize(context, folder.totalSizeBytes) else ""
            )
            showOrHide(
                binding.folderDateTextView,
                if (folder.latestDateModifiedMs > 0L) {
                    DateUtils.formatDateTime(
                        context,
                        folder.latestDateModifiedMs,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
                    )
                } else {
                    ""
                }
            )
            loadCover(binding.thumbnailImageView, folder)
        }
    }

    private fun showOrHide(view: android.widget.TextView, text: String) {
        view.text = text
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindCount(chip: android.widget.TextView, folder: MediaFolder) {
        chip.text = formatCount(folder.itemCount)
        // A negative count means "not known (yet)".
        chip.visibility = if (folder.itemCount < 0) View.GONE else View.VISIBLE
        TonalCountChipStyler.apply(chip)
    }

    /** The cover picture, or the icon of the folder (or of its source, e.g. a network share). */
    private fun loadCover(imageView: ImageView, folder: MediaFolder) {
        val thumb = folder.thumbnail
        val thumbSource = thumb?.let { MediaSourceRegistry.forPath(it) }
        val canLoadThumb = thumb != null && (thumbSource?.canLoadThumbnail(thumb) ?: true)
        if (canLoadThumb) {
            Glide.with(imageView.context)
                .load(thumb)
                .centerCrop()
                .placeholder(R.drawable.ic_folder)
                .error(R.drawable.ic_folder)
                .into(imageView)
        } else {
            Glide.with(imageView.context).clear(imageView)
            val iconRes = if (folder.isSubFolder) {
                R.drawable.ic_folder
            } else {
                MediaSourceRegistry.forPath(folder.path)?.folderIconRes ?: R.drawable.ic_folder
            }
            imageView.setImageResource(iconRes)
        }
    }

    private fun formatCount(count: Int): String {
        return when {
            count < 1000 -> NumberFormat.getNumberInstance().format(count)
            count < 10_000 -> String.format("%.1fk", count / 1000.0)
            count < 1_000_000 -> "${count / 1000}k"
            else -> String.format("%.1fM", count / 1_000_000.0)
        }
    }

    /** Date and size in one short line under a card. */
    private fun RecyclerView.ViewHolder.shortMeta(folder: MediaFolder): String {
        // Remote entries without date/size info (e.g. a network share) leave the meta empty.
        if (MediaSourceRegistry.isManaged(folder.path) &&
            folder.latestDateModifiedMs <= 0L &&
            folder.totalSizeBytes <= 0L
        ) {
            return ""
        }

        val context = itemView.context
        val datePart = if (folder.latestDateModifiedMs > 0L) {
            DateUtils.formatDateTime(
                context,
                folder.latestDateModifiedMs,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
            )
        } else null

        val sizePart = if (folder.totalSizeBytes > 0L) {
            Formatter.formatShortFileSize(context, folder.totalSizeBytes)
        } else null

        return when {
            datePart != null && sizePart != null ->
                context.getString(R.string.folder_meta_format, datePart, sizePart)
            datePart != null -> datePart
            sizePart != null -> sizePart
            else -> ""
        }
    }

    private class FolderDiffCallback : DiffUtil.ItemCallback<MediaFolder>() {
        override fun areItemsTheSame(oldItem: MediaFolder, newItem: MediaFolder): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: MediaFolder, newItem: MediaFolder): Boolean {
            return oldItem == newItem
        }
    }

    private companion object {
        const val VIEW_TYPE_CARD = 0
        const val VIEW_TYPE_LIST = 1
        const val VIEW_TYPE_DETAILED = 2

        /** Name size of the small cards (three side by side). */
        const val COMPACT_NAME_SCALE = 0.82f
    }
}
