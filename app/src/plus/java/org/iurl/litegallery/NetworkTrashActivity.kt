package org.iurl.litegallery

import android.app.AlertDialog
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iurl.litegallery.databinding.ActivityTrashBinBinding
import org.iurl.litegallery.databinding.ItemTrashBinding
import org.iurl.litegallery.theme.ThemeColorResolver
import org.iurl.litegallery.theme.ThemeVariant

/**
 * The trash bin of a network share ([SmbTrash]). It looks and works like the trash bin for
 * files on the phone: restore or delete single files, or select several.
 */
class NetworkTrashActivity : AppCompatActivity() {

    companion object {
        /** URL of any location on the share (e.g. smb://host/share). */
        const val EXTRA_SHARE_URL = "extra_share_url"
    }

    private lateinit var binding: ActivityTrashBinBinding
    private lateinit var adapter: NetworkTrashAdapter

    private var shareUrl = ""
    private var items: List<SmbTrash.Item> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        ThemeHelper.applyPackTheme(this, ThemeVariant.NoActionBar)

        super.onCreate(savedInstanceState)
        ThemeHelper.captureCustomThemeGeneration(this)
        binding = ActivityTrashBinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            windowInsets
        }

        shareUrl = SmbTrash.shareRootOf(intent.getStringExtra(EXTRA_SHARE_URL).orEmpty()).orEmpty()
        if (shareUrl.isEmpty()) {
            finish()
            return
        }

        setupToolbar()
        setupRecyclerView()
        setupSelectionBar()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selected.isNotEmpty()) clearSelection() else finish()
                }
            }
        )

        binding.trashScopeNoteTextView.text = getString(R.string.network_trash_scope_note, shareLabel())
        ThemeHelper.applyRuntimeCustomColors(this)
        loadItems()
    }

    private fun shareLabel(): String {
        val smb = SmbPath.parse(shareUrl) ?: return shareUrl
        val server = NetworkConfigStore.getAllServers(this).firstOrNull {
            !it.isNfs &&
                it.host.equals(smb.host, ignoreCase = true) &&
                it.share.equals(smb.share, ignoreCase = true)
        }
        return server?.displayName?.takeIf { it.isNotBlank() } ?: "${smb.host}/${smb.share}"
    }

    // ---------------------------------------------------------------- setup

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            if (selected.isNotEmpty()) clearSelection() else finish()
        }
        binding.toolbar.inflateMenu(R.menu.trash_bin_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_restore_all -> {
                    confirm(R.string.restore_all_confirmation) { restore(items) }
                    true
                }
                R.id.action_empty_trash -> {
                    confirm(R.string.empty_trash_confirmation) { deleteForGood(items) }
                    true
                }
                R.id.action_select_all -> {
                    selected.addAll(items.map { it.trashedUrl })
                    refreshRows()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = NetworkTrashAdapter(
            onItemClick = { item -> toggleSelection(item) },
            onItemLongClick = { item -> toggleSelection(item) },
            onRestoreClick = { item -> restore(listOf(item)) },
            onDeleteClick = { item ->
                confirm(R.string.file_op_delete_selected_confirmation, 1) { deleteForGood(listOf(item)) }
            },
            isItemSelected = { item -> selected.contains(item.trashedUrl) },
            isInSelectionMode = { selected.isNotEmpty() },
            remainLabel = { item -> remainLabelFor(item) }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerView.adapter = adapter
    }

    private fun setupSelectionBar() {
        binding.clearSelectionButton.setOnClickListener { clearSelection() }
        binding.restoreSelectedButton.setOnClickListener {
            restore(items.filter { selected.contains(it.trashedUrl) })
        }
        binding.deleteSelectedButton.setOnClickListener {
            val chosen = items.filter { selected.contains(it.trashedUrl) }
            confirm(R.string.file_op_delete_selected_confirmation, chosen.size) { deleteForGood(chosen) }
        }
    }

    // ---------------------------------------------------------------- data

    private fun loadItems() {
        lifecycleScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    // Files that stayed longer than the retention time are erased first.
                    SmbTrash.purgeExpired(
                        applicationContext,
                        shareUrl,
                        TrashBinStore.getTrashRetentionDays(applicationContext)
                    )
                    SmbTrash.list(applicationContext, shareUrl)
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkTrashActivity", "Could not read the trash of $shareUrl", e)
                null
            }

            if (loaded == null) {
                Toast.makeText(this@NetworkTrashActivity, R.string.file_op_folder_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            items = loaded
            selected.retainAll(loaded.map { it.trashedUrl }.toSet())
            adapter.submitList(loaded)
            refreshRows()
        }
    }

    private fun remainLabelFor(item: SmbTrash.Item): String? {
        val retentionDays = TrashBinStore.getTrashRetentionDays(this)
        if (retentionDays <= 0 || item.deletedAt <= 0L) return null
        val ageDays = (System.currentTimeMillis() - item.deletedAt) / (24L * 60L * 60L * 1000L)
        val left = (retentionDays - ageDays).coerceAtLeast(0L)
        return getString(R.string.trash_remain_days_format, left.toInt())
    }

    // ---------------------------------------------------------------- selection

    private fun toggleSelection(item: SmbTrash.Item) {
        if (!selected.remove(item.trashedUrl)) selected.add(item.trashedUrl)
        refreshRows()
    }

    private fun clearSelection() {
        selected.clear()
        refreshRows()
    }

    /** Redraws the rows and the surrounding screen after the list or the selection changed. */
    private fun refreshRows() {
        adapter.notifyDataSetChanged()

        val retentionDays = TrashBinStore.getTrashRetentionDays(this)
        binding.heroSubtitleTextView.text = if (retentionDays > 0) {
            getString(R.string.trash_subtitle_format, items.size, retentionDays)
        } else {
            getString(R.string.trash_subtitle_no_retention_format, items.size)
        }
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        val selecting = selected.isNotEmpty()
        binding.selectionActionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.selectedCountTextView.text = getString(R.string.trash_selected_count, selected.size)

        val menu = binding.toolbar.menu
        menu.findItem(R.id.action_restore_all)?.apply { isVisible = !selecting; isEnabled = items.isNotEmpty() }
        menu.findItem(R.id.action_empty_trash)?.apply { isVisible = !selecting; isEnabled = items.isNotEmpty() }
        menu.findItem(R.id.action_select_all)?.apply { isVisible = selecting; isEnabled = selected.size < items.size }
    }

    // ---------------------------------------------------------------- actions

    private fun confirm(messageRes: Int, vararg args: Any, onConfirmed: () -> Unit) {
        if (isBusy) return
        AlertDialog.Builder(this)
            .setMessage(getString(messageRes, *args))
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restore(list: List<SmbTrash.Item>) {
        runOperation(R.string.network_trash_restoring, list) { item ->
            val restored = SmbTrash.restore(applicationContext, item)
            if (restored) {
                // The folder the file returned to must show it again.
                SmbPath.parse(item.originalUrl)?.let { FolderMediaRepository.invalidate(it.parentFolderPath) }
            }
            restored
        }
    }

    private fun deleteForGood(list: List<SmbTrash.Item>) {
        runOperation(R.string.file_op_deleting, list) { item ->
            SmbTrash.deletePermanently(applicationContext, item)
        }
    }

    private fun runOperation(titleRes: Int, list: List<SmbTrash.Item>, action: (SmbTrash.Item) -> Boolean) {
        if (list.isEmpty() || isBusy) return
        isBusy = true

        FileOperationDialogs.runWithProgress(
            activity = this,
            titleRes = titleRes,
            total = list.size,
            work = { onProgress ->
                var done = 0
                var failed = 0
                list.forEachIndexed { index, item ->
                    val ok = try {
                        action(item)
                    } catch (e: Exception) {
                        android.util.Log.e("NetworkTrashActivity", "Operation failed: ${item.trashedUrl}", e)
                        false
                    }
                    if (ok) done++ else failed++
                    onProgress(index + 1, list.size)
                }
                FileTransfer.Result(done, failed, emptyList(), emptyList())
            },
            onDone = {
                isBusy = false
                selected.clear()
                loadItems()
            }
        )
    }
}

/** Card grid of the trashed files of a network share (same cards as the phone's trash bin). */
class NetworkTrashAdapter(
    private val onItemClick: (SmbTrash.Item) -> Unit,
    private val onItemLongClick: (SmbTrash.Item) -> Unit,
    private val onRestoreClick: (SmbTrash.Item) -> Unit,
    private val onDeleteClick: (SmbTrash.Item) -> Unit,
    private val isItemSelected: (SmbTrash.Item) -> Boolean,
    private val isInSelectionMode: () -> Boolean,
    private val remainLabel: (SmbTrash.Item) -> String?
) : ListAdapter<SmbTrash.Item, NetworkTrashAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemTrashBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SmbTrash.Item) {
            val context = binding.root.context

            // Cover from the thumbnail cache of the share, faded to show it is deleted.
            Glide.with(context)
                .load("smbthumb://${item.trashedUrl}")
                .centerCrop()
                .placeholder(R.drawable.ic_folder)
                .error(R.drawable.ic_folder)
                .into(binding.thumbnailImageView)
            binding.thumbnailImageView.colorFilter =
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.35f) })

            binding.sourceBadgeTextView.visibility = View.GONE

            val remain = remainLabel(item)
            if (remain.isNullOrBlank()) {
                binding.remainBadgeTextView.visibility = View.GONE
            } else {
                binding.remainBadgeTextView.visibility = View.VISIBLE
                binding.remainBadgeTextView.text = remain
                TonalCountChipStyler.apply(binding.remainBadgeTextView)
            }

            val folderName = item.originalFolderName
            if (folderName.isBlank()) {
                binding.fromLabelTextView.visibility = View.GONE
            } else {
                binding.fromLabelTextView.visibility = View.VISIBLE
                binding.fromLabelTextView.text = context.getString(R.string.trash_from_label, folderName)
            }

            val selected = isItemSelected(item)
            val density = context.resources.displayMetrics.density
            binding.root.strokeWidth = ((if (selected) 2f else 1f) * density).toInt()
            binding.root.strokeColor = ThemeColorResolver.resolveColor(
                context,
                if (selected) {
                    com.google.android.material.R.attr.colorPrimary
                } else {
                    com.google.android.material.R.attr.colorOutlineVariant
                }
            )

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
            binding.restoreButton.setOnClickListener {
                if (isInSelectionMode()) onItemClick(item) else onRestoreClick(item)
            }
            binding.permanentDeleteButton.setOnClickListener {
                if (isInSelectionMode()) onItemClick(item) else onDeleteClick(item)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<SmbTrash.Item>() {
        override fun areItemsTheSame(oldItem: SmbTrash.Item, newItem: SmbTrash.Item): Boolean =
            oldItem.trashedUrl == newItem.trashedUrl

        override fun areContentsTheSame(oldItem: SmbTrash.Item, newItem: SmbTrash.Item): Boolean =
            oldItem == newItem
    }
}
