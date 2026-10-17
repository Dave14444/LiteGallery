package org.iurl.litegallery

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.ListPreloader
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import org.iurl.litegallery.databinding.ActivityFolderViewBinding
import org.iurl.litegallery.theme.GradientHelper
import org.iurl.litegallery.theme.ThemeColorResolver
import org.iurl.litegallery.theme.ThemeVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class FolderViewActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
        const val EXTRA_FOLDER_NAME = "extra_folder_name"
        private const val SWIPE_REFRESH_THROTTLE_MS = 1_200L
        private const val PREF_DEFAULT_SORT_ORDER = "default_sort_order"
        private const val PREF_REMEMBER_FOLDER_SORT_ORDER = "remember_folder_sort_order"
        private const val PREF_LAST_FOLDER_SORT_ORDER = "last_folder_sort_order"
        private const val PREF_DEFAULT_VIEW_MODE = "default_view_mode"
        private const val PREF_REMEMBER_FOLDER_VIEW_MODE = "remember_folder_view_mode"
        private const val PREF_LAST_FOLDER_VIEW_MODE = "last_folder_view_mode"
        private const val PREF_DEFAULT_GROUP_BY = "default_group_by"
        private const val PREF_REMEMBER_FOLDER_GROUP_BY = "remember_folder_group_by"
        private const val PREF_LAST_FOLDER_GROUP_BY = "last_folder_group_by"
        private const val FAST_SCROLL_JUMP_VIEWPORT_MULTIPLIER = 2
        private const val SEARCH_PROMPT_TOP_MARGIN_DP = 32
        private const val SEARCH_PROMPT_HORIZONTAL_PADDING_DP = 24
        private const val SEARCH_PROMPT_VERTICAL_PADDING_DP = 16
        // Same preference as the viewer's delete dialog.
        private const val DELETE_PREFS = "delete_prefs"
        private const val DELETE_MOVE_TO_TRASH_KEY = "delete_move_to_trash"
    }
    
    private lateinit var binding: ActivityFolderViewBinding
    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var groupedMediaAdapter: GroupedMediaAdapter
    private lateinit var mediaScanner: MediaScanner
    
    private var folderPath: String = ""
    private var folderName: String = ""
    private var unfilteredMediaItems: List<MediaItemSkeleton> = emptyList()
    private var mediaItems: List<MediaItemSkeleton> = emptyList()
    private var currentPackKey: String? = null
    private var currentViewMode: MediaAdapter.ViewMode = MediaAdapter.ViewMode.GRID
    private var currentSortOrder: String = "date_desc"
    private var currentGroupBy: FolderGroupBy = FolderGroupBy.NONE
    private var sortMenuItem: MenuItem? = null
    private var searchMenuItem: MenuItem? = null
    private var searchView: SearchView? = null
    private var isLoadingMediaItems = false
    private var lastSwipeRefreshAtMs = 0L
    private var transformJob: Job? = null
    private var mediaIndexSyncJob: Job? = null
    private var displayGeneration = 0
    private var fastScrollSections: List<FastScrollSection> = emptyList()
    private var lockedFastScrollRange = 0
    private var swipeRefreshEnabledBeforeFastScroll = true
    private val pendingMetadataIds = mutableSetOf<Long>()
    private var deferFastScrollerUntilFinalLoad = false
    private var suppressSearchEvents = false
    private var currentSearchName = ""
    private var currentSearchFilters = SearchFilterState()
    private var currentSearchQuery: MediaSearchQuery? = null
    private var appliedSearchTitle = ""
    private val emptySearchKey = SearchQueryKey("", SearchFilterState())
    private var lastAppliedSearchKey = emptySearchKey

    private data class SearchFilterState(
        val typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
        val dateRange: TimeRange? = null,
        val sizeRangeBytes: LongRange? = null
    )

    private data class SearchQueryKey(
        val normalizedNameQuery: String,
        val filters: SearchFilterState
    )

    private val selectedPaths = LinkedHashSet<String>()
    private var copyMenuItem: MenuItem? = null
    private var moveMenuItem: MenuItem? = null
    private var deleteMenuItem: MenuItem? = null
    private var selectAllMenuItem: MenuItem? = null

    private val selectionBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            clearSelection()
        }
    }

    private val mediaViewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val hasMediaChanged = result.resultCode == RESULT_OK &&
            data?.getBooleanExtra(MediaViewerActivity.RESULT_MEDIA_CHANGED, false) == true
        if (!hasMediaChanged) return@registerForActivityResult

        val changedFolderPath = data?.getStringExtra(MediaViewerActivity.RESULT_FOLDER_PATH)
        if (changedFolderPath.isNullOrEmpty() || changedFolderPath == folderPath) {
            if (!applyViewerResultDeltas(data)) {
                FolderMediaRepository.invalidate(folderPath)
                loadMediaItems(showBlockingLoading = displayedItemCount == 0)
            }
        }

        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(MediaViewerActivity.RESULT_MEDIA_CHANGED, true)
                putExtra(MediaViewerActivity.RESULT_FOLDER_PATH, changedFolderPath ?: folderPath)
            }
        )
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme pack before setting content view
        ThemeHelper.applyTheme(this)
        ThemeHelper.applyPackTheme(this, ThemeVariant.NoActionBar)

        super.onCreate(savedInstanceState)
        ThemeHelper.captureCustomThemeGeneration(this)
        binding = ActivityFolderViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle window insets for navigation bar
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val navigationInsets = insets.getInsets(android.view.WindowInsets.Type.navigationBars())
            val statusInsets = insets.getInsets(android.view.WindowInsets.Type.statusBars())
            // Apply padding to prevent navigation bar overlap
            view.setPadding(0, statusInsets.top, 0, navigationInsets.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, selectionBackCallback)

        folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: ""
        folderName = intent.getStringExtra(EXTRA_FOLDER_NAME) ?: ""
        
        // Track current pack so onResume can detect changes from Settings/Picker
        currentPackKey = ThemeHelper.getCurrentPack(this).key

        // Load default view mode and sort order from preferences
        loadViewModePreference()
        loadSortOrderPreference()
        loadGroupByPreference()

        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupSearchFilters()
        setupFastScroller()
        ThemeHelper.applyRuntimeCustomColors(this)
        applyFolderHeroGradientAccent()
        binding.root.post { applyFolderHeroGradientAccent() }

        mediaScanner = MediaScanner(this)
        loadMediaItems()
    }

    override fun onDestroy() {
        transformJob?.cancel()
        mediaIndexSyncJob?.cancel()
        resetDetailedMetadataRequests()
        super.onDestroy()
    }
    
    override fun onResume() {
        super.onResume()
        // Apply theme in case it was changed in settings
        ThemeHelper.applyTheme(this)

        // Recreate if the pack changed (e.g., user picked a new pack from settings)
        val newPackKey = ThemeHelper.getCurrentPack(this).key
        if (currentPackKey != null && currentPackKey != newPackKey) {
            recreate()
            return
        }
        currentPackKey = newPackKey
        if (ThemeHelper.checkAndRecreateForCustomThemeChange(this)) return
        applyFolderHeroGradientAccent()
        synchronizeMediaIndexInBackground()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.folder_view_menu, menu)
        sortMenuItem = menu.findItem(R.id.action_sort)
        searchMenuItem = menu.findItem(R.id.action_search)
        copyMenuItem = menu.findItem(R.id.action_copy_selection)
        moveMenuItem = menu.findItem(R.id.action_move_selection)
        deleteMenuItem = menu.findItem(R.id.action_delete_selection)
        selectAllMenuItem = menu.findItem(R.id.action_select_all)
        setupSearchMenuItem(searchMenuItem)
        updateSortIndicator()
        updateSelectionUi()
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (selectedPaths.isNotEmpty()) clearSelection() else finish()
                true
            }
            R.id.action_copy_selection -> {
                startTransferOfSelection(FileTransfer.Mode.COPY)
                true
            }
            R.id.action_move_selection -> {
                startTransferOfSelection(FileTransfer.Mode.MOVE)
                true
            }
            R.id.action_delete_selection -> {
                confirmDeleteSelected()
                true
            }
            R.id.action_select_all -> {
                selectAllVisible()
                true
            }
            R.id.action_view_mode -> {
                toggleViewMode()
                true
            }
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    // ---- Selection, copy / move / delete (local files and sources that support it) ----

    private fun canSelect(skeleton: MediaItemSkeleton): Boolean {
        val source = MediaSourceRegistry.forPath(skeleton.path)
        return if (source != null) {
            source.supportsFileOperations(skeleton.path)
        } else {
            skeleton.path.startsWith("/")
        }
    }

    private fun toggleSelection(skeleton: MediaItemSkeleton) {
        if (!canSelect(skeleton)) return
        if (!selectedPaths.remove(skeleton.path)) selectedPaths.add(skeleton.path)
        notifySelectionChanged(listOf(skeleton.path))
        updateSelectionUi()
    }

    private fun selectAllVisible() {
        val added = mediaItems.filter { canSelect(it) }.map { it.path }
        selectedPaths.addAll(added)
        notifySelectionChanged(added)
        updateSelectionUi()
    }

    private fun clearSelection() {
        if (selectedPaths.isEmpty()) return
        val previous = selectedPaths.toList()
        selectedPaths.clear()
        notifySelectionChanged(previous)
        updateSelectionUi()
    }

    private fun notifySelectionChanged(paths: Collection<String>) {
        if (::mediaAdapter.isInitialized) mediaAdapter.notifySelectionChanged(paths)
        if (::groupedMediaAdapter.isInitialized) groupedMediaAdapter.notifySelectionChanged(paths)
    }

    private fun updateSelectionUi() {
        val selecting = selectedPaths.isNotEmpty()
        supportActionBar?.title = if (selecting) {
            getString(R.string.selection_count, selectedPaths.size)
        } else {
            ""
        }
        copyMenuItem?.isVisible = selecting
        moveMenuItem?.isVisible = selecting
        deleteMenuItem?.isVisible = selecting
        selectAllMenuItem?.isVisible = selecting
        selectionBackCallback.isEnabled = selecting
    }

    private fun selectedItems(): List<MediaItemSkeleton> =
        unfilteredMediaItems.filter { it.path in selectedPaths }

    private fun startTransferOfSelection(mode: FileTransfer.Mode) {
        val items = selectedItems()
        if (items.isEmpty()) return

        // Moving files on the phone needs all-files access (copying only reads them).
        if (mode == FileTransfer.Mode.MOVE &&
            items.any { FileTransfer.isLocalPath(it.path) } &&
            !StorageAccess.ensure(this)
        ) {
            return
        }

        DestinationPicker.show(this, mode) { destFolder ->
            if (FileTransfer.isLocalPath(destFolder) && !StorageAccess.ensure(this)) {
                return@show
            }
            val paths = items.map { it.path }
            FileOperationDialogs.runWithProgress(
                activity = this,
                titleRes = if (mode == FileTransfer.Mode.COPY) {
                    R.string.file_op_copying
                } else {
                    R.string.file_op_moving
                },
                total = paths.size,
                work = { onProgress ->
                    FileTransfer.transfer(applicationContext, paths, destFolder, mode, onProgress)
                },
                onDone = { result -> onFileOperationDone(result, destFolder) }
            )
        }
    }

    private fun confirmDeleteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return

        // Same dialog as in the viewer. Only files on the phone can go to the trash.
        val hasLocal = items.any { FileTransfer.isLocalPath(it.path) }
        if (hasLocal && !StorageAccess.ensure(this)) return

        val prefs = getSharedPreferences(DELETE_PREFS, MODE_PRIVATE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_media, null)
        val messageTextView = dialogView.findViewById<android.widget.TextView>(R.id.deleteMessageTextView)
        val moveToTrashCheckbox = dialogView.findViewById<android.widget.CheckBox>(R.id.moveToTrashCheckBox)
        messageTextView.text = getString(R.string.file_op_delete_selected_confirmation, items.size)
        moveToTrashCheckbox.isChecked = hasLocal && prefs.getBoolean(DELETE_MOVE_TO_TRASH_KEY, true)
        moveToTrashCheckbox.visibility = if (hasLocal) View.VISIBLE else View.GONE

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setView(dialogView)
            .setPositiveButton(R.string.delete, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            val updatePositiveButtonText = {
                val textRes = if (hasLocal && moveToTrashCheckbox.isChecked) {
                    R.string.move_to_trash
                } else {
                    R.string.delete_permanently
                }
                positiveButton.text = getString(textRes)
            }
            updatePositiveButtonText()
            moveToTrashCheckbox.setOnCheckedChangeListener { _, _ -> updatePositiveButtonText() }

            positiveButton.setOnClickListener {
                val moveToTrash = hasLocal && moveToTrashCheckbox.isChecked
                if (hasLocal) {
                    prefs.edit().putBoolean(DELETE_MOVE_TO_TRASH_KEY, moveToTrash).apply()
                }
                dialog.dismiss()
                FileOperationDialogs.runWithProgress(
                    activity = this,
                    titleRes = R.string.file_op_deleting,
                    total = items.size,
                    work = { onProgress ->
                        FileTransfer.delete(applicationContext, items, moveToTrash, onProgress)
                    },
                    onDone = { result -> onFileOperationDone(result, null) }
                )
            }
        }
        dialog.show()
        ThemeHelper.applyRuntimeCustomColors(dialog)
    }

    /** Brings the screen up to date after files were copied, moved or deleted. */
    private fun onFileOperationDone(result: FileTransfer.Result, destFolder: String?) {
        clearSelection()

        FolderMediaRepository.invalidate(folderPath)
        if (destFolder != null) FolderMediaRepository.invalidate(destFolder)

        if (result.removedPaths.isNotEmpty()) {
            result.removedPaths.filter { FileTransfer.isLocalPath(it) }.forEach { path ->
                lifecycleScope.launch { mediaScanner.removeIndexedMediaPath(path) }
            }
            val removedIntent = Intent().putStringArrayListExtra(
                MediaViewerActivity.RESULT_DELETED_PATHS,
                ArrayList(result.removedPaths)
            )
            if (!applyViewerResultDeltas(removedIntent)) {
                loadMediaItems(showBlockingLoading = false)
            }
        }

        if (result.done > 0) {
            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(MediaViewerActivity.RESULT_MEDIA_CHANGED, true)
                    putExtra(MediaViewerActivity.RESULT_FOLDER_PATH, folderPath)
                }
            )
        }

        // New files in the folder that is shown right now.
        if (destFolder != null &&
            destFolder.trimEnd('/') == folderPath.trimEnd('/') &&
            result.createdPaths.isNotEmpty()
        ) {
            reloadAfterScan(result.createdPaths)
        }
    }

    private fun reloadAfterScan(createdPaths: List<String>) {
        val local = createdPaths.filter { FileTransfer.isLocalPath(it) }
        if (local.isEmpty()) {
            loadMediaItems(showBlockingLoading = false)
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(local.size)
        android.media.MediaScannerConnection.scanFile(this, local.toTypedArray(), null) { _, _ ->
            if (remaining.decrementAndGet() == 0) {
                runOnUiThread { loadMediaItems(showBlockingLoading = false) }
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            // Title lives in the hero block below the toolbar, not the toolbar itself.
            title = ""
        }
        bindFolderHeroTitle()
        binding.folderHeroDivider.visibility = View.GONE
        binding.folderStatsTextView.visibility = View.GONE
        binding.groupStatusChip.setOnClickListener { showGroupDialog() }
        binding.sortStatusChip.setOnClickListener { showSortDialog() }
        updateGroupIndicator()
        updateSortIndicator()
    }

    private fun bindFolderHeroTitle() {
        if (currentSearchQuery != null) {
            binding.folderEyebrowTextView.text = getString(R.string.folder_search_eyebrow_format, folderName)
            binding.folderTitleTextView.text = appliedSearchTitle.ifBlank { folderName }
        } else {
            binding.folderEyebrowTextView.setText(R.string.folder_eyebrow)
            binding.folderTitleTextView.text = folderName
        }
    }

    private fun applyFolderHeroGradientAccent() {
        val gradient = GradientHelper.createForCurrentPack(this, binding.heroContainer.radius) ?: return
        val onGradient = ThemeColorResolver.resolveColor(
            this,
            com.google.android.material.R.attr.colorOnPrimary,
            Color.WHITE,
        )

        binding.folderHeroInnerLayout.background = gradient
        binding.folderHeroInnerLayout.setTag(R.id.tag_custom_theme_skip_subtree, true)
        binding.heroContainer.strokeWidth = 0
        binding.heroContainer.setCardBackgroundColor(Color.TRANSPARENT)
        binding.folderHeroDivider.setBackgroundColor(onGradient.withAlpha(0x33))
        binding.folderEyebrowTextView.setTextColor(onGradient)
        binding.folderTitleTextView.setTextColor(onGradient)
        binding.folderStatsTextView.setTextColor(onGradient)
    }

    private fun Int.withAlpha(alpha: Int): Int =
        (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).roundToInt()

    private fun bindFolderStats(stats: FolderDisplayStats) {
        val showSearchCount = currentSearchQuery != null && unfilteredMediaItems.isNotEmpty()
        if (stats.itemCount == 0 && !showSearchCount) {
            binding.folderHeroDivider.visibility = View.GONE
            binding.folderStatsTextView.visibility = View.GONE
            return
        }
        val parts = mutableListOf(
            if (showSearchCount) {
                getString(R.string.search_count_format, stats.itemCount, unfilteredMediaItems.size)
            } else {
                getString(R.string.items_count, stats.itemCount)
            }
        )
        val totalSize = stats.totalSizeBytes
        if (totalSize > 0L) {
            parts.add(Formatter.formatShortFileSize(this, totalSize))
        }
        val videoCount = stats.videoCount
        if (videoCount > 0) {
            parts.add(getString(R.string.folder_stat_videos_format, videoCount))
        }
        binding.folderStatsTextView.text = parts.joinToString(" · ")
        binding.folderHeroDivider.visibility = View.VISIBLE
        binding.folderStatsTextView.visibility = View.VISIBLE
    }
    
    private fun setupRecyclerView() {
        mediaAdapter = MediaAdapter(
            onMediaClick = { mediaItem, position ->
                if (selectedPaths.isNotEmpty()) {
                    toggleSelection(mediaItem)
                } else {
                    openMediaViewer(mediaItem, position)
                }
            },
            onMediaLongClick = { mediaItem, _ -> toggleSelection(mediaItem) },
            isItemSelected = { mediaItem -> mediaItem.path in selectedPaths },
            onDetailedMetadataNeeded = ::requestDetailedMetadataForVisibleItem
        )
        groupedMediaAdapter = GroupedMediaAdapter(
            onMediaClick = { mediaItem, mediaIndex ->
                if (selectedPaths.isNotEmpty()) {
                    toggleSelection(mediaItem)
                } else {
                    openMediaViewer(mediaItem, mediaIndex)
                }
            },
            onDetailedMetadataNeeded = ::requestDetailedMetadataForVisibleItem,
            onMediaLongClick = { mediaItem, _ -> toggleSelection(mediaItem) },
            isItemSelected = { mediaItem -> mediaItem.path in selectedPaths }
        )

        binding.recyclerView.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(20)
         //   addOnScrollListener(createThumbnailPreloader())
            
            adapter = if (currentGroupBy == FolderGroupBy.NONE) {
                mediaAdapter
            } else {
                groupedMediaAdapter
            }
        }

        // Apply view mode
        updateLayoutManager()
    }

    private fun createThumbnailPreloader(): RecyclerViewPreloader<Any> {
        val requestManager = Glide.with(this)
        val preloadModelProvider = object : ListPreloader.PreloadModelProvider<Any> {
            override fun getPreloadItems(position: Int): MutableList<Any> {
                val skeleton = when (binding.recyclerView.adapter) {
                    mediaAdapter -> mediaAdapter.currentList.getOrNull(position)
                    groupedMediaAdapter -> (groupedMediaAdapter.currentList.getOrNull(position) as? FolderDisplayItem.Media)?.skeleton
                    else -> null
                }
                if (skeleton == null || skeleton.usesPlaceholderThumbnail) return mutableListOf()
                
val thumbnailModel = if (skeleton.path.startsWith("smb://")) {
    "smbthumb://${skeleton.path}"
} else {
    skeleton.thumbnailModel()
}
return mutableListOf(thumbnailModel)

            }

            override fun getPreloadRequestBuilder(item: Any): RequestBuilder<Drawable>? {
                return requestManager.load(item).centerCrop()
            }
        }
        val preloadSizeProvider = object : ListPreloader.PreloadSizeProvider<Any> {
            override fun getPreloadSize(item: Any, adapterPosition: Int, perItemPosition: Int): IntArray {
                val sizePx = (96 * resources.displayMetrics.density).roundToInt().coerceAtLeast(96)
                return intArrayOf(sizePx, sizePx)
            }
        }
        return RecyclerViewPreloader(requestManager, preloadModelProvider, preloadSizeProvider, 20)
    }

    private val displayedItemCount: Int
        get() {
            if (!::mediaAdapter.isInitialized || !::groupedMediaAdapter.isInitialized) return 0
            return when (binding.recyclerView.adapter) {
                groupedMediaAdapter -> groupedMediaAdapter.itemCount
                mediaAdapter -> mediaAdapter.itemCount
                else -> 0
            }
        }

   private fun openMediaViewer(skeleton: MediaItemSkeleton, position: Int) {
    // Die aktuell sichtbare, bereits sortierte Liste für den Viewer sichern.
    // Sie ist die maßgebliche Reihenfolge der Ordneransicht.
    cacheCurrentFolderMedia(
        items = mediaItems,
        isComplete = true
    )


    val intent = Intent(this, MediaViewerActivity::class.java).apply {
        putExtra(MediaViewerActivity.EXTRA_MEDIA_PATH, skeleton.path)
        putExtra(MediaViewerActivity.EXTRA_FOLDER_PATH, folderPath)
        putExtra(MediaViewerActivity.EXTRA_CURRENT_POSITION, position)
    }
    mediaViewerLauncher.launch(intent)
}

    private fun cacheCurrentFolderMedia(
        items: List<MediaItemSkeleton> = mediaItems,
        isComplete: Boolean
    ) {
        if (items.isEmpty()) {
            FolderMediaRepository.invalidate(folderPath)
            return
        }
        FolderMediaRepository.putSkeleton(
            folderPath = folderPath,
            items = items,
            isComplete = isComplete,
            sortOrder = currentSortOrder,
            groupBy = currentGroupBy
        )
    }

    private fun requestDetailedMetadataForVisibleItem(skeleton: MediaItemSkeleton) {
        if (skeleton.id <= MediaItem.NO_MEDIASTORE_ID) return
        if (skeleton.isRemote || skeleton.path.isBlank()) return
        if (MediaMetadataCache.get(skeleton) != null) return
        if (!pendingMetadataIds.add(skeleton.id)) return

        lifecycleScope.launch {
            try {
                val cachedItems = mediaScanner.getCachedMediaByIds(listOf(skeleton.id))
                val item = cachedItems.firstOrNull() ?: return@launch
                MediaMetadataCache.put(item)
                notifyMetadataLoaded(item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                pendingMetadataIds.remove(skeleton.id)
            }
        }
    }

    private fun applyViewerResultDeltas(data: Intent?): Boolean {
        if (data == null || unfilteredMediaItems.isEmpty()) return false

        val deletedPaths = data.getStringArrayListExtra(MediaViewerActivity.RESULT_DELETED_PATHS)
            ?.toSet()
            .orEmpty()
        val renamedOldPaths = data.getStringArrayListExtra(MediaViewerActivity.RESULT_RENAMED_OLD_PATHS).orEmpty()
        val renamedNewPaths = data.getStringArrayListExtra(MediaViewerActivity.RESULT_RENAMED_NEW_PATHS).orEmpty()
        val renamedNewNames = data.getStringArrayListExtra(MediaViewerActivity.RESULT_RENAMED_NEW_NAMES).orEmpty()
        val renamedIds = data.getLongArrayExtra(MediaViewerActivity.RESULT_RENAMED_IDS) ?: LongArray(0)

        if (deletedPaths.isEmpty() && renamedOldPaths.isEmpty()) return false

        val updatedItems = unfilteredMediaItems.toMutableList()
        var changed = false

        for (index in updatedItems.indices.reversed()) {
            if (updatedItems[index].path in deletedPaths) {
                MediaMetadataCache.remove(updatedItems[index].id)
                updatedItems.removeAt(index)
                changed = true
            }
        }

        val renameCount = minOf(renamedOldPaths.size, renamedNewPaths.size, renamedNewNames.size)
        repeat(renameCount) { renameIndex ->
            val oldPath = renamedOldPaths[renameIndex]
            val itemIndex = updatedItems.indexOfFirst { it.path == oldPath }
            if (itemIndex < 0) return@repeat

            val current = updatedItems[itemIndex]
            val updatedId = renamedIds.getOrNull(renameIndex)
                ?.takeIf { it > MediaItem.NO_MEDIASTORE_ID }
                ?: current.id
            val updated = current.copy(
                id = updatedId,
                path = renamedNewPaths[renameIndex],
                name = renamedNewNames[renameIndex]
            )
            updatedItems[itemIndex] = updated
            MediaMetadataCache.get(current.id)?.let { cached ->
                if (current.id != updatedId) MediaMetadataCache.remove(current.id)
                MediaMetadataCache.updatePath(updatedId, cached.copy(id = updatedId, name = updated.name, path = updated.path))
            }
            changed = true
        }

        if (!changed) return true

        unfilteredMediaItems = updatedItems
        if (unfilteredMediaItems.isEmpty()) {
            mediaItems = emptyList()
            clearDisplayedItems()
            showFolderEmptyState()
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            applyDisplayTransform(scrollToTop = false, bypassDiff = true)
        }
        return true
    }

    private fun notifyMetadataLoaded(item: MediaItem) {
        if (binding.recyclerView.adapter == groupedMediaAdapter) {
            val adapterPos = groupedMediaAdapter.currentList.indexOfFirst {
                it is FolderDisplayItem.Media && it.skeleton.id == item.id
            }
            if (adapterPos >= 0) {
                groupedMediaAdapter.notifyItemChanged(adapterPos, MediaAdapter.PAYLOAD_META_LOADED)
            }
        } else {
            val adapterPos = mediaAdapter.currentList.indexOfFirst { it.id == item.id }
            if (adapterPos >= 0) {
                mediaAdapter.notifyItemChanged(adapterPos, MediaAdapter.PAYLOAD_META_LOADED)
            }
        }
    }

    private fun prefetchMetadataRange() {
        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return

        val from = (first - 20).coerceAtLeast(0)
        val to = (last + 50).coerceAtMost(displayedItemCount - 1)

        val skeletonsToFetch = mutableListOf<MediaItemSkeleton>()
        val isGrouped = binding.recyclerView.adapter == groupedMediaAdapter
        
        for (i in from..to) {
            val skeleton = if (isGrouped) {
                val item = groupedMediaAdapter.getItem(i)
                if (item is FolderDisplayItem.Media) item.skeleton else null
            } else {
                mediaAdapter.getItem(i)
            }
            if (
                skeleton != null &&
                skeleton.id > MediaItem.NO_MEDIASTORE_ID &&
                !skeleton.isRemote &&
                MediaMetadataCache.get(skeleton) == null &&
                skeleton.id !in pendingMetadataIds
            ) {
                skeletonsToFetch.add(skeleton)
            }
        }

        if (skeletonsToFetch.isEmpty()) return

        val idsToFetch = skeletonsToFetch.map { it.id }.distinct()
        pendingMetadataIds.addAll(idsToFetch)

        lifecycleScope.launch {
            try {
                val cachedItems = mediaScanner.getCachedMediaByIds(idsToFetch)
                if (cachedItems.isEmpty()) return@launch

                cachedItems.forEach { item ->
                    MediaMetadataCache.put(item)
                }

                cachedItems.forEach { item ->
                    notifyMetadataLoaded(item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                idsToFetch.forEach { pendingMetadataIds.remove(it) }
            }
        }
    }

    private fun resetDetailedMetadataRequests() {
        pendingMetadataIds.clear()
    }

    private fun setupSearchMenuItem(item: MenuItem?) {
        val view = item?.actionView as? SearchView ?: return
        searchView = view
        view.queryHint = getString(R.string.search_hint_folder)
        view.maxWidth = Int.MAX_VALUE
        view.setIconifiedByDefault(false)
        view.setSubmitButtonEnabled(false)
        view.imeOptions = EditorInfo.IME_ACTION_SEARCH
        view.findViewById<View>(androidx.appcompat.R.id.search_mag_icon)?.setOnClickListener {
            submitFolderSearchFromView(view)
        }
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (suppressSearchEvents) return true
                submitFolderSearchFromView(view)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (suppressSearchEvents) return true
                currentSearchName = newText.orEmpty()
                updateSearchFilterChips()
                return true
            }
        })
        view.setOnCloseListener {
            searchMenuItem?.collapseActionView()
            true
        }
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                binding.searchFilterRow.visibility = View.VISIBLE
                updateSearchFilterChips()
                view.post {
                    view.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(view.findFocus() ?: view, InputMethodManager.SHOW_IMPLICIT)
                }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                binding.searchFilterRow.visibility = View.GONE
                clearFolderSearch(collapseSearchView = false)
                return true
            }
        })
    }

    private fun submitFolderSearchFromView(view: SearchView) {
        currentSearchName = view.query?.toString().orEmpty()
        applyFolderSearchNow(scrollToTop = true)
        view.clearFocus()
    }

    private fun setupSearchFilters() {
        binding.searchTypeChip.setOnClickListener {
            val nextType = when (currentSearchFilters.typeFilter) {
                MediaTypeFilter.ALL -> MediaTypeFilter.IMAGES
                MediaTypeFilter.IMAGES -> MediaTypeFilter.VIDEOS
                MediaTypeFilter.VIDEOS -> MediaTypeFilter.ALL
            }
            currentSearchFilters = currentSearchFilters.copy(typeFilter = nextType)
            applyFolderSearchNow(scrollToTop = true)
        }
        binding.searchDateChip.setOnClickListener {
            SearchFilterUi.showDateRangeDialog(this, currentSearchFilters.dateRange) { range ->
                currentSearchFilters = currentSearchFilters.copy(dateRange = range)
                applyFolderSearchNow(scrollToTop = true)
            }
        }
        binding.searchSizeChip.setOnClickListener {
            SearchFilterUi.showSizeRangeDialog(this, currentSearchFilters.sizeRangeBytes) { range ->
                currentSearchFilters = currentSearchFilters.copy(sizeRangeBytes = range)
                applyFolderSearchNow(scrollToTop = true)
            }
        }
        binding.searchClearChip.setOnClickListener {
            clearFolderSearch(collapseSearchView = false)
        }
        binding.folderClearFiltersButton.setOnClickListener {
            clearFolderSearch(collapseSearchView = false)
        }
    }

    private fun clearFolderSearch(collapseSearchView: Boolean) {
        currentSearchName = ""
        currentSearchFilters = SearchFilterState()

        suppressSearchEvents = true
        searchView?.setQuery("", false)
        searchView?.clearFocus()
        suppressSearchEvents = false

        applyFolderSearchNow(scrollToTop = true, force = true)
        if (collapseSearchView) {
            searchMenuItem?.collapseActionView()
        }
    }

    private fun applyFolderSearchNow(scrollToTop: Boolean, force: Boolean = false) {
        val normalizedName = NameMatcher.normalizePattern(currentSearchName)
        val key = SearchQueryKey(normalizedName, currentSearchFilters)
        val nextSearchQuery = buildFolderSearchQueryOrNull(normalizedName)
        if (!force && key == lastAppliedSearchKey) {
            currentSearchQuery = nextSearchQuery
            appliedSearchTitle = if (nextSearchQuery != null) currentSearchName.trim() else ""
            bindFolderHeroTitle()
            updateSearchFilterChips()
            return
        }

        lastAppliedSearchKey = key
        currentSearchQuery = nextSearchQuery
        appliedSearchTitle = if (nextSearchQuery != null) currentSearchName.trim() else ""
        bindFolderHeroTitle()
        updateSearchFilterChips()
        applyDisplayTransform(scrollToTop = scrollToTop, bypassDiff = true)
    }

    private fun buildFolderSearchQueryOrNull(normalizedName: String): MediaSearchQuery? {
        val query = MediaSearchQuery(
            normalizedNameQuery = normalizedName,
            nameMatcher = NameMatcher.compile(normalizedName),
            typeFilter = currentSearchFilters.typeFilter,
            dateRange = currentSearchFilters.dateRange,
            sizeRangeBytes = currentSearchFilters.sizeRangeBytes
        )
        return query.takeUnless { it.isEmpty }
    }

    private fun updateSearchFilterChips() {
        binding.searchTypeChip.text = when (currentSearchFilters.typeFilter) {
            MediaTypeFilter.ALL -> getString(R.string.search_chip_type_all)
            MediaTypeFilter.IMAGES -> getString(R.string.search_chip_type_images)
            MediaTypeFilter.VIDEOS -> getString(R.string.search_chip_type_videos)
        }
        binding.searchDateChip.text = SearchFilterUi.formatDateRange(this, currentSearchFilters.dateRange)
        binding.searchSizeChip.text = SearchFilterUi.formatSizeRange(this, currentSearchFilters.sizeRangeBytes)
        binding.searchClearChip.visibility = if (hasAnySearchInput()) View.VISIBLE else View.GONE
    }

    private fun hasAnySearchInput(): Boolean =
        currentSearchName.isNotBlank() || currentSearchFilters != SearchFilterState()

    private fun synchronizeMediaIndexInBackground() {
        if (!::mediaScanner.isInitialized || isLoadingMediaItems || MediaSourceRegistry.isManaged(folderPath)) return
        if (mediaIndexSyncJob?.isActive == true) return

        mediaIndexSyncJob = lifecycleScope.launch {
            try {
                mediaScanner.synchronizeMediaIndexIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSwipeRefreshAtMs < SWIPE_REFRESH_THROTTLE_MS) {
                binding.swipeRefresh.isRefreshing = false
                return@setOnRefreshListener
            }
            lastSwipeRefreshAtMs = now

            loadMediaItems(showBlockingLoading = false, fromSwipeRefresh = true)
        }
        binding.swipeRefresh.setColorSchemeColors(
            ThemeColorResolver.resolveColor(this, com.google.android.material.R.attr.colorPrimary),
        )
    }

    private fun setupFastScroller() {
        binding.recyclerView.isVerticalScrollBarEnabled = false
        binding.fastScrollerView.setSections(emptyList())
        binding.fastScrollerView.setEnabledForContent(false)
        binding.fastScrollerView.setDragListener(object : FolderFastScrollerView.DragListener {
            override fun onDragStarted(): Boolean {
                if (binding.swipeRefresh.isRefreshing) return false

                val scrollRange = binding.recyclerView.computeVerticalScrollRange()
                val scrollExtent = binding.recyclerView.computeVerticalScrollExtent()
                val scrollableRange = scrollRange - scrollExtent
                if (scrollableRange <= 0) return false

                lockedFastScrollRange = scrollableRange
                swipeRefreshEnabledBeforeFastScroll = binding.swipeRefresh.isEnabled
                binding.swipeRefresh.isEnabled = false
                return true
            }

            override fun onDragMoved(fraction: Float) {
                val scrollableRange = lockedFastScrollRange
                if (scrollableRange <= 0) return

                val desiredOffset = (fraction * scrollableRange).roundToInt()
                val delta = desiredOffset - binding.recyclerView.computeVerticalScrollOffset()
                if (delta != 0) {
                    scrollRecyclerViewForFastDrag(fraction, delta)
                }
                syncFastScrollerFromRecyclerView(showForScroll = false)
            }

            override fun onDragStopped() {
                lockedFastScrollRange = 0
                binding.swipeRefresh.isEnabled = swipeRefreshEnabledBeforeFastScroll
            }
        })

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                syncFastScrollerFromRecyclerView(showForScroll = dy != 0)
                prefetchMetadataRange()
            }
        })
    }



    private fun scrollRecyclerViewForFastDrag(fraction: Float, delta: Int) {
        val recyclerView = binding.recyclerView
        val jumpThreshold = recyclerView.height * FAST_SCROLL_JUMP_VIEWPORT_MULTIPLIER
        if (kotlin.math.abs(delta) <= jumpThreshold) {
            recyclerView.scrollBy(0, delta)
            return
        }

        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount <= 0) return

        val targetPosition = (fraction * (itemCount - 1)).roundToInt()
            .coerceIn(0, itemCount - 1)
        (recyclerView.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(targetPosition, 0)
    }

    private fun syncFastScrollerFromRecyclerView(showForScroll: Boolean) {
        val recyclerView = binding.recyclerView
        val scrollOffset = recyclerView.computeVerticalScrollOffset()
        val scrollRange = recyclerView.computeVerticalScrollRange()
        val scrollExtent = recyclerView.computeVerticalScrollExtent()
        val canScroll = recyclerView.visibility == View.VISIBLE &&
            displayedItemCount > 0 &&
            scrollRange > scrollExtent &&
            !deferFastScrollerUntilFinalLoad

        binding.fastScrollerView.setScrollMetrics(scrollOffset, scrollRange, scrollExtent)
        binding.fastScrollerView.setEnabledForContent(canScroll)
        updateFastScrollerSectionTitle()

        if (canScroll && showForScroll) {
            binding.fastScrollerView.show()
            binding.fastScrollerView.hideDelayed()
        }
    }

    private fun updateFastScrollerSectionTitle() {
        val firstVisiblePosition =
            (binding.recyclerView.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition()
                ?: RecyclerView.NO_POSITION
        val title = FastScrollSectionIndex.titleForPosition(fastScrollSections, firstVisiblePosition)
        binding.fastScrollerView.setCurrentSectionTitle(title)
    }

    private fun disableFastScrollerForListMutation() {
        fastScrollSections = emptyList()
        binding.fastScrollerView.setSections(emptyList())
        binding.fastScrollerView.setCurrentSectionTitle(null)
        binding.fastScrollerView.setEnabledForContent(false)
    }

    private fun applyFastScrollSectionsAfterCommit(sections: List<FastScrollSection>) {
        fastScrollSections = sections
        binding.fastScrollerView.setSections(sections)
        updateFastScrollerSectionTitle()
        binding.recyclerView.post {
            syncFastScrollerFromRecyclerView(showForScroll = false)
        }
    }

    private fun updateLayoutManager() {
        binding.recyclerView.layoutManager = when (currentViewMode) {
            MediaAdapter.ViewMode.GRID -> GridLayoutManager(this, 3).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return if (
                            binding.recyclerView.adapter == groupedMediaAdapter &&
                            groupedMediaAdapter.isHeaderPosition(position)
                        ) {
                            spanCount
                        } else {
                            1
                        }
                    }
                }
            }
            MediaAdapter.ViewMode.LIST, MediaAdapter.ViewMode.DETAILED -> LinearLayoutManager(this)
        }
        mediaAdapter.viewMode = currentViewMode
        groupedMediaAdapter.viewMode = currentViewMode
        binding.recyclerView.post {
            syncFastScrollerFromRecyclerView(showForScroll = false)
        }
    }

    private fun loadViewModePreference() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val defaultViewMode = prefs.getString(PREF_DEFAULT_VIEW_MODE, "grid") ?: "grid"
        val rememberViewMode = prefs.getBoolean(PREF_REMEMBER_FOLDER_VIEW_MODE, false)
        val resolvedViewMode = if (rememberViewMode) {
            prefs.getString(PREF_LAST_FOLDER_VIEW_MODE, defaultViewMode)
        } else {
            defaultViewMode
        }
        currentViewMode = parseViewModePreference(resolvedViewMode)

        // Initialize remembered value when feature is enabled for the first time.
        if (rememberViewMode && !prefs.contains(PREF_LAST_FOLDER_VIEW_MODE)) {
            prefs.edit().putString(PREF_LAST_FOLDER_VIEW_MODE, viewModeToPreferenceValue(currentViewMode)).apply()
        }
    }

    private fun toggleViewMode() {
        currentViewMode = when (currentViewMode) {
            MediaAdapter.ViewMode.GRID -> MediaAdapter.ViewMode.LIST
            MediaAdapter.ViewMode.LIST -> MediaAdapter.ViewMode.DETAILED
            MediaAdapter.ViewMode.DETAILED -> MediaAdapter.ViewMode.GRID
        }
        updateLayoutManager()
        persistCurrentViewModeIfNeeded()

        // Show toast to indicate current view mode
        val modeName = when (currentViewMode) {
            MediaAdapter.ViewMode.GRID -> getString(R.string.thumbnail_view)
            MediaAdapter.ViewMode.LIST -> getString(R.string.list_view)
            MediaAdapter.ViewMode.DETAILED -> getString(R.string.detailed_view)
        }
        android.widget.Toast.makeText(this, modeName, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun parseViewModePreference(viewModeValue: String?): MediaAdapter.ViewMode {
        return when (viewModeValue) {
            "list" -> MediaAdapter.ViewMode.LIST
            "detailed" -> MediaAdapter.ViewMode.DETAILED
            else -> MediaAdapter.ViewMode.GRID
        }
    }

    private fun viewModeToPreferenceValue(viewMode: MediaAdapter.ViewMode): String {
        return when (viewMode) {
            MediaAdapter.ViewMode.GRID -> "grid"
            MediaAdapter.ViewMode.LIST -> "list"
            MediaAdapter.ViewMode.DETAILED -> "detailed"
        }
    }

    private fun persistCurrentViewModeIfNeeded() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(PREF_REMEMBER_FOLDER_VIEW_MODE, false)) return
        prefs.edit().putString(PREF_LAST_FOLDER_VIEW_MODE, viewModeToPreferenceValue(currentViewMode)).apply()
    }

    private fun loadSortOrderPreference() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val defaultSortOrder = parseSortOrderPreference(
            prefs.getString(PREF_DEFAULT_SORT_ORDER, "date_desc")
        )
        val rememberSortOrder = prefs.getBoolean(PREF_REMEMBER_FOLDER_SORT_ORDER, false)
        currentSortOrder = if (rememberSortOrder) {
            parseSortOrderPreference(prefs.getString(PREF_LAST_FOLDER_SORT_ORDER, defaultSortOrder))
        } else {
            defaultSortOrder
        }

        // Initialize remembered value when feature is enabled for the first time.
        if (rememberSortOrder && !prefs.contains(PREF_LAST_FOLDER_SORT_ORDER)) {
            prefs.edit().putString(PREF_LAST_FOLDER_SORT_ORDER, currentSortOrder).apply()
        }
    }

    private fun loadGroupByPreference() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val defaultGroupBy = FolderGroupBy.fromPreference(
            prefs.getString(PREF_DEFAULT_GROUP_BY, FolderGroupBy.DATE.preferenceValue)
        )
        val rememberGroupBy = prefs.getBoolean(PREF_REMEMBER_FOLDER_GROUP_BY, false)
        currentGroupBy = if (rememberGroupBy) {
            FolderGroupBy.fromPreference(prefs.getString(PREF_LAST_FOLDER_GROUP_BY, defaultGroupBy.preferenceValue))
        } else {
            defaultGroupBy
        }

        if (rememberGroupBy && !prefs.contains(PREF_LAST_FOLDER_GROUP_BY)) {
            prefs.edit().putString(PREF_LAST_FOLDER_GROUP_BY, currentGroupBy.preferenceValue).apply()
        }
    }

    private fun parseSortOrderPreference(sortOrderValue: String?): String {
        return when (sortOrderValue) {
            "date_desc", "date_asc", "name_asc", "name_desc", "size_desc", "size_asc" -> sortOrderValue
            else -> "date_desc"
        }
    }

    private fun sortOrderLabel(sortOrder: String = currentSortOrder): String {
        return when (sortOrder) {
            "date_desc" -> getString(R.string.folder_sort_chip_date_desc)
            "date_asc" -> getString(R.string.folder_sort_chip_date_asc)
            "name_asc" -> getString(R.string.folder_sort_chip_name_asc)
            "name_desc" -> getString(R.string.folder_sort_chip_name_desc)
            "size_desc" -> getString(R.string.folder_sort_chip_size_desc)
            "size_asc" -> getString(R.string.folder_sort_chip_size_asc)
            else -> getString(R.string.folder_sort_chip_date_desc)
        }
    }

    private fun updateSortIndicator() {
        val label = sortOrderLabel()
        val description = getString(R.string.folder_sort_content_description, label)
        binding.sortStatusChip.text = label
        binding.sortStatusChip.contentDescription = description
        sortMenuItem?.title = description
    }

    private fun groupByLabel(groupBy: FolderGroupBy = currentGroupBy): String {
        return when (groupBy) {
            FolderGroupBy.NONE -> getString(R.string.group_none)
            FolderGroupBy.DATE -> getString(R.string.group_date)
            FolderGroupBy.NAME -> getString(R.string.group_name)
            FolderGroupBy.SIZE -> getString(R.string.group_size)
            FolderGroupBy.TYPE -> getString(R.string.group_type)
        }
    }

    private fun updateGroupIndicator() {
        val label = groupByLabel()
        val chipText = getString(R.string.folder_group_chip_format, label)
        binding.groupStatusChip.text = chipText
        binding.groupStatusChip.contentDescription =
            getString(R.string.folder_group_content_description, label)
    }

    private fun persistCurrentSortOrderIfNeeded() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(PREF_REMEMBER_FOLDER_SORT_ORDER, false)) return
        prefs.edit().putString(PREF_LAST_FOLDER_SORT_ORDER, currentSortOrder).apply()
    }

    private fun persistCurrentGroupByIfNeeded() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(PREF_REMEMBER_FOLDER_GROUP_BY, false)) return
        prefs.edit().putString(PREF_LAST_FOLDER_GROUP_BY, currentGroupBy.preferenceValue).apply()
    }

    private fun showSortDialog() {
        val sortOptions = arrayOf(
            getString(R.string.sort_by_date_desc),
            getString(R.string.sort_by_date_asc),
            getString(R.string.sort_by_name_asc),
            getString(R.string.sort_by_name_desc),
            getString(R.string.sort_by_size_desc),
            getString(R.string.sort_by_size_asc)
        )

        val sortValues = arrayOf("date_desc", "date_asc", "name_asc", "name_desc", "size_desc", "size_asc")

        val currentIndex = sortValues.indexOf(currentSortOrder).takeIf { it >= 0 } ?: 0

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.sort)
            .setSingleChoiceItems(sortOptions, currentIndex) { dialog, which ->
                currentSortOrder = parseSortOrderPreference(sortValues[which])
                applyDisplayTransform(scrollToTop = true, bypassDiff = true)
                persistCurrentSortOrderIfNeeded()
                updateSortIndicator()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .showThemed()
    }

    private fun showGroupDialog() {
        val groupValues = arrayOf(
            FolderGroupBy.DATE,
            FolderGroupBy.NAME,
            FolderGroupBy.SIZE,
            FolderGroupBy.TYPE,
            FolderGroupBy.NONE
        )
        val groupOptions = groupValues.map { groupByLabel(it) }.toTypedArray()
        val currentIndex = groupValues.indexOf(currentGroupBy).takeIf { it >= 0 } ?: 0

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.group_by)
            .setSingleChoiceItems(groupOptions, currentIndex) { dialog, which ->
                currentGroupBy = groupValues[which]
                applyDisplayTransform(scrollToTop = true, bypassDiff = true)
                persistCurrentGroupByIfNeeded()
                updateGroupIndicator()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .showThemed()
    }

    private fun android.app.AlertDialog.Builder.showThemed(): android.app.AlertDialog {
        val dialog = create()
        dialog.show()
        ThemeHelper.applyRuntimeCustomColors(dialog)
        return dialog
    }

    private fun applyDisplayTransform(scrollToTop: Boolean, bypassDiff: Boolean = false) {
        transformJob?.cancel()
        if (isLoadingMediaItems) return
        if (unfilteredMediaItems.isEmpty()) {
            mediaItems = emptyList()
            FolderMediaRepository.invalidate(folderPath)
            clearDisplayedItems()
            showFolderEmptyState()
            return
        }

        val generation = ++displayGeneration
        val sourceItems = unfilteredMediaItems
        val sortOrderSnapshot = currentSortOrder
        val groupBySnapshot = currentGroupBy
        val searchQuerySnapshot = currentSearchQuery
        val labels = folderDisplayLabels()

        transformJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                FolderDisplayBuilder.build(
                    items = sourceItems,
                    sortOrder = sortOrderSnapshot,
                    groupBy = groupBySnapshot,
                    labels = labels,
                    searchQuery = searchQuerySnapshot,
                    ensureActive = { coroutineContext.ensureActive() }
                )
            }
            if (generation != displayGeneration) return@launch

            mediaItems = result.sortedMediaItems
            cacheCurrentFolderMedia(mediaItems, isComplete = currentSearchQuery == null)
            submitDisplayResult(result, scrollToTop, bypassDiff)
            bindFolderContentVisibility()
        }
    }

    private fun submitDisplayResult(
        result: FolderDisplayResult,
        scrollToTop: Boolean = false,
        bypassDiff: Boolean = false
    ) {
        disableFastScrollerForListMutation()
        bindFolderStats(result.stats)
        if (result.isGrouped) {
            if (binding.recyclerView.adapter != groupedMediaAdapter) {
                binding.recyclerView.adapter = groupedMediaAdapter
            }
            groupedMediaAdapter.viewMode = currentViewMode
            groupedMediaAdapter.submitList(result.displayItems, bypassDiff = bypassDiff) {
                if (scrollToTop) binding.recyclerView.scrollToPosition(0)
                applyFastScrollSectionsAfterCommit(result.fastScrollSections)
            }
        } else {
            if (binding.recyclerView.adapter != mediaAdapter) {
                binding.recyclerView.adapter = mediaAdapter
            }
            mediaAdapter.viewMode = currentViewMode
            mediaAdapter.submitList(result.sortedMediaItems, bypassDiff = bypassDiff) {
                if (scrollToTop) binding.recyclerView.scrollToPosition(0)
                applyFastScrollSectionsAfterCommit(result.fastScrollSections)
            }
        }
    }

    private fun submitLoadingSkeletons(
        items: List<MediaItemSkeleton>,
        deltaItems: List<MediaItemSkeleton> = emptyList(),
        replace: Boolean
    ) {
        if (binding.recyclerView.adapter != mediaAdapter) {
            binding.recyclerView.adapter = mediaAdapter
        }
        mediaAdapter.viewMode = currentViewMode
        if (replace) {
            disableFastScrollerForListMutation()
            mediaAdapter.submitList(items, bypassDiff = true)
        } else {
            mediaAdapter.appendSkeletons(deltaItems)
        }
    }

    private fun clearDisplayedItems() {
        disableFastScrollerForListMutation()
        bindFolderStats(FolderDisplayStats.EMPTY)
        mediaAdapter.submitList(emptyList())
        groupedMediaAdapter.submitList(emptyList())
    }

    private fun showFolderEmptyState() {
        applyFolderEmptyLayout()
        val noResultsFromSearch = currentSearchQuery != null && unfilteredMediaItems.isNotEmpty()
        binding.folderEmptyTextView.setText(
            if (noResultsFromSearch) R.string.search_no_results else R.string.no_media_found
        )
        binding.folderClearFiltersButton.visibility = if (noResultsFromSearch) View.VISIBLE else View.GONE
        binding.emptyView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
    }

    private fun applyFolderEmptyLayout() {
        val params = binding.emptyView.layoutParams as? CoordinatorLayout.LayoutParams ?: return
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.topMargin = SEARCH_PROMPT_TOP_MARGIN_DP.dpToPx()
        binding.emptyView.layoutParams = params
        val horizontalPadding = SEARCH_PROMPT_HORIZONTAL_PADDING_DP.dpToPx()
        val verticalPadding = SEARCH_PROMPT_VERTICAL_PADDING_DP.dpToPx()
        binding.emptyView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
    }

    private fun bindFolderContentVisibility() {
        if (mediaItems.isEmpty()) {
            showFolderEmptyState()
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    private fun folderDisplayLabels(): FolderDisplayLabels {
        val appContext = applicationContext
        return FolderDisplayLabels(
            unknownDate = getString(R.string.group_unknown_date),
            unknownSize = getString(R.string.group_unknown_size),
            imageType = getString(R.string.group_type_image),
            videoType = getString(R.string.group_type_video),
            otherType = getString(R.string.group_type_other),
            formatSize = { bytes -> Formatter.formatShortFileSize(appContext, bytes) }
        )
    }

    private suspend fun buildDisplayResultForCurrentState(items: List<MediaItemSkeleton>): FolderDisplayResult {
        while (true) {
            val sortOrderSnapshot = currentSortOrder
            val groupBySnapshot = currentGroupBy
            val searchQuerySnapshot = currentSearchQuery
            val searchKeySnapshot = lastAppliedSearchKey
            val labels = folderDisplayLabels()
            val result = withContext(Dispatchers.Default) {
                FolderDisplayBuilder.build(
                    items = items,
                    sortOrder = sortOrderSnapshot,
                    groupBy = groupBySnapshot,
                    labels = labels,
                    searchQuery = searchQuerySnapshot,
                    ensureActive = { coroutineContext.ensureActive() }
                )
            }
            if (sortOrderSnapshot == currentSortOrder &&
                groupBySnapshot == currentGroupBy &&
                searchKeySnapshot == lastAppliedSearchKey
            ) {
                return result
            }
        }
    }

    private fun loadMediaItems(
        showBlockingLoading: Boolean = true,
        fromSwipeRefresh: Boolean = false
    ) {
        if (isLoadingMediaItems) {
            if (fromSwipeRefresh) {
                binding.swipeRefresh.isRefreshing = false
            }
            return
        }

        if (showBlockingLoading) {
            binding.progressBar.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.GONE
            binding.fastScrollerView.setEnabledForContent(false)
        } else if (fromSwipeRefresh) {
            binding.swipeRefresh.isRefreshing = true
        }

        isLoadingMediaItems = true
        deferFastScrollerUntilFinalLoad = true
        
        lifecycleScope.launch {
            val loadedSkeletons = ArrayList<MediaItemSkeleton>()
            try {
                transformJob?.cancel()
                resetDetailedMetadataRequests()

                if (fromSwipeRefresh && !MediaSourceRegistry.isManaged(folderPath)) {
                    mediaScanner.synchronizeMediaIndexIfNeeded()
                }
                
                FolderMediaRepository.loadFolderStreamed(this@FolderViewActivity, folderPath)
                    .collect { event ->
                        when (event) {
                            is LoadEvent.FirstScreen -> {
                                ++displayGeneration
                                loadedSkeletons.clear()
                                loadedSkeletons.addAll(event.items)
                                unfilteredMediaItems = loadedSkeletons.toList()
                                mediaItems = unfilteredMediaItems
                                cacheCurrentFolderMedia(mediaItems, isComplete = false)
                                submitLoadingSkeletons(mediaItems, replace = true)
                                
                                binding.progressBar.visibility = View.GONE
                                if (mediaItems.isEmpty()) {
                                    showFolderEmptyState()
                                    binding.recyclerView.visibility = View.GONE
                                } else {
                                    binding.emptyView.visibility = View.GONE
                                    binding.recyclerView.visibility = View.VISIBLE
                                }
                            }
                            is LoadEvent.Progress -> {
                                if (event.deltaItems.isNotEmpty()) {
                                    loadedSkeletons.addAll(event.deltaItems)
                                    unfilteredMediaItems = loadedSkeletons.toList()
                                    mediaItems = unfilteredMediaItems
                                    cacheCurrentFolderMedia(mediaItems, isComplete = false)
                                    submitLoadingSkeletons(
                                        items = mediaItems,
                                        deltaItems = event.deltaItems,
                                        replace = false
                                    )
                                    binding.emptyView.visibility = View.GONE
                                    binding.recyclerView.visibility = View.VISIBLE
                                }

                                if (event.isFinal) {
                                    val generation = ++displayGeneration
                                    unfilteredMediaItems = loadedSkeletons.toList()
                                    val displayResult = buildDisplayResultForCurrentState(unfilteredMediaItems)
                                    if (generation != displayGeneration) return@collect

                                    deferFastScrollerUntilFinalLoad = false
                                    mediaItems = displayResult.sortedMediaItems
                                    cacheCurrentFolderMedia(mediaItems, isComplete = currentSearchQuery == null)
                                    submitDisplayResult(displayResult, bypassDiff = true)

                                    binding.progressBar.visibility = View.GONE
                                    bindFolderContentVisibility()
                                    isLoadingMediaItems = false
                                    binding.swipeRefresh.isRefreshing = false
                                    binding.recyclerView.post {
                                        syncFastScrollerFromRecyclerView(showForScroll = false)
                                    }
                                }
                            }
                            is LoadEvent.Failed -> {
                                deferFastScrollerUntilFinalLoad = false
                                binding.progressBar.visibility = View.GONE
                                showFolderEmptyState()
                                binding.recyclerView.visibility = View.GONE
                                binding.fastScrollerView.setEnabledForContent(false)
                                isLoadingMediaItems = false
                                binding.swipeRefresh.isRefreshing = false
                            }
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deferFastScrollerUntilFinalLoad = false
                binding.progressBar.visibility = View.GONE
                showFolderEmptyState()
                binding.recyclerView.visibility = View.GONE
                binding.fastScrollerView.setEnabledForContent(false)
                isLoadingMediaItems = false
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }
}
