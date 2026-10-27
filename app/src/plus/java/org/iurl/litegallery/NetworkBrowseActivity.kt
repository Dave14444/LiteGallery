package org.iurl.litegallery

import androidx.activity.OnBackPressedCallback
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.iurl.litegallery.databinding.ActivityNetworkBrowseBinding
import org.iurl.litegallery.theme.ThemeColorResolver
import org.iurl.litegallery.theme.ThemeVariant

/**
 * Activity for browsing network shares (SMB and NFS). Provides an address bar for manual
 * entry, saved-server bookmarks, and the folders of a share as cards - the same cards as on
 * the home screen. Pictures and videos open in the regular folder screen, exactly like local
 * folders.
 */
class NetworkBrowseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_PATH = "extra_start_path"

        /** Marks the "images in this folder" card (the folder's own pictures) in [MediaFolder.path]. */
        private const val IMAGES_CARD_SUFFIX = "#images"

        /** Folder summaries (cover, count) read at the same time. */
        private const val SUMMARY_PARALLEL = 3
    }

    private lateinit var binding: ActivityNetworkBrowseBinding
    private lateinit var browseAdapter: FolderAdapter
    private lateinit var fileListAdapter: RemoteListAdapter
    private lateinit var remoteMediaScanner: RemoteMediaScanner
    private var currentPath: String = ""
    private var isLoading = false
    private var currentPackKey: String? = null
    private var summaryJob: Job? = null
    /** Scroll position (first visible item, offset) of the folders left behind, by folder URL. */
    private val scrollPositions = HashMap<String, IntArray>()
    private var launchedWithPath = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        ThemeHelper.applyPackTheme(this, ThemeVariant.NoActionBar)

        super.onCreate(savedInstanceState)
        ThemeHelper.captureCustomThemeGeneration(this)
        binding = ActivityNetworkBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle window insets
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val navigationInsets = insets.getInsets(android.view.WindowInsets.Type.navigationBars())
            val statusInsets = insets.getInsets(android.view.WindowInsets.Type.statusBars())
            view.setPadding(0, statusInsets.top, 0, navigationInsets.bottom)
            insets
        }

        currentPackKey = ThemeHelper.getCurrentPack(this).key
        remoteMediaScanner = RemoteMediaScanner(this)

        // Back gesture (including predictive back): one folder up, or leave the screen.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentPath.isNotBlank()) {
                        navigateUp()
                    } else {
                        finish()
                    }
                }
            }
        )

        setupToolbar()
        setupAddressBar()
        setupRecyclerView()
        setupSavedServers()
        setupSwipeRefresh()
        ThemeHelper.applyRuntimeCustomColors(this)

        // Handle intent
        val intentPath = intent.getStringExtra(EXTRA_START_PATH)
        if (!intentPath.isNullOrBlank()) {
            launchedWithPath = true
            binding.addressEditText.setText(intentPath)
            navigateTo(intentPath)
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeHelper.applyTheme(this)
        val newPackKey = ThemeHelper.getCurrentPack(this).key
        if (currentPackKey != null && currentPackKey != newPackKey) {
            recreate()
            return
        }
        currentPackKey = newPackKey
        if (ThemeHelper.checkAndRecreateForCustomThemeChange(this)) return

        // Back from another app: pictures it saved may have been moved next to the original.
        ExternalEditFlow.checkResult(this) {
            if (currentPath.isNotBlank()) navigateTo(currentPath)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (currentPath.isNotBlank()) {
                    navigateUp()
                } else {
                    finish()
                }
                true
            }
            R.id.action_network_trash -> {
                SmbTrash.shareRootOf(currentPath)?.let { shareRoot ->
                    startActivity(
                        Intent(this, NetworkTrashActivity::class.java)
                            .putExtra(NetworkTrashActivity.EXTRA_SHARE_URL, shareRoot)
                    )
                }
                true
            }
            R.id.action_prepare_thumbnails -> {
                val label = currentPath.trimEnd('/').substringAfterLast('/')
                    .ifBlank { getString(R.string.network_browse_title) }
                ThumbnailPreparer.start(this, currentPath, label)
                true
            }
            R.id.action_cleanup_thumbnails -> {
                ThumbnailCleanup.start(this, currentPath)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.network_browse_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // The trash belongs to a share: offer it while a folder on an SMB share is open.
        menu.findItem(R.id.action_network_trash)?.isVisible = SmbPath.isSmb(currentPath)
        menu.findItem(R.id.action_prepare_thumbnails)?.isVisible = SmbPath.isSmb(currentPath)
        menu.findItem(R.id.action_cleanup_thumbnails)?.isVisible = SmbPath.isSmb(currentPath)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.network_browse_title)
        }
        
        binding.homeButton.setOnClickListener {
            goHome()
        }
    }

    private fun goHome() {
        currentPath = ""
        invalidateOptionsMenu()
        refreshSavedServers()
        browseAdapter.submitList(emptyList())
        binding.recyclerView.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        binding.addressEditText.setText("")
        supportActionBar?.title = getString(R.string.network_browse_title)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            if (currentPath.isNotBlank()) {
                navigateTo(currentPath)
            } else {
                refreshSavedServers()
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
        
        binding.swipeRefreshLayout.setColorSchemeColors(
            ThemeColorResolver.resolveColor(this, com.google.android.material.R.attr.colorPrimary),
        )
    }

    private fun setupAddressBar() {
        binding.connectButton.setOnClickListener {
            val address = binding.addressEditText.text.toString().trim()
            if (address.isBlank()) {
                Toast.makeText(this, getString(R.string.smb_enter_address), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val normalized = when {
                address.startsWith("smb://") || address.startsWith("nfs://") -> address
                address.startsWith("\\\\") || address.startsWith("//") ->
                    "smb://${address.removePrefix("\\\\").removePrefix("//").replace('\\', '/')}"
                else -> "smb://$address"
            }

            if (binding.addressEditText.text.toString() != normalized) {
                binding.addressEditText.setText(normalized)
                binding.addressEditText.setSelection(normalized.length)
            }

            if (NfsPath.isNfs(normalized)) {
                val nfsPath = NfsPath.parse(normalized)
                if (nfsPath == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.smb_invalid_address),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val hasConfig = NetworkConfigStore.getAllServers(this).any {
                    it.isNfs && it.host.equals(nfsPath.host, ignoreCase = true) &&
                        NfsPath.matchExport(listOf(it.export), nfsPath.fullPath) != null
                }
                if (hasConfig) {
                    navigateTo(normalized)
                } else {
                    // No saved export covers this URL: collect one via the add dialog.
                    showAddServerDialog(prefillNfsHost = nfsPath.host)
                }
                return@setOnClickListener
            }

            // SMB flow
            val smbPath = SmbPath.parse(normalized)
            if (smbPath == null) {
                Toast.makeText(this, getString(R.string.smb_invalid_address), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check if we have saved credentials for this exact path
            val allConfigs = NetworkConfigStore.getAllServers(this)
            val exactConfig = allConfigs.find {
                !it.isNfs &&
                    it.host.equals(smbPath.host, ignoreCase = true) &&
                it.share.equals(smbPath.share, ignoreCase = true) &&
                it.path.equals(smbPath.path, ignoreCase = true)
            }
            if (exactConfig != null) {
                navigateTo(normalized)
                return@setOnClickListener
            }

            // Otherwise, check if we have ANY credentials for this host
            val hostConfig = allConfigs.find {
                !it.isNfs && it.host.equals(smbPath.host, ignoreCase = true)
            }
            if (hostConfig != null) {
                navigateTo(normalized)
            } else {
                val defaultName = smbPath.host + if (smbPath.path.isNotBlank()) " - " + smbPath.path.substringAfterLast('/') else if (smbPath.share.isNotBlank()) " - " + smbPath.share else ""
                showConnectDialog(smbPath.host, smbPath.share, smbPath.path, defaultName) {
                    navigateTo(normalized)
                }
            }
        }

        // Handle keyboard enter
        binding.addressEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                binding.connectButton.performClick()
                true
            } else {
                false
            }
        }

        // Update star state when address changes
        binding.addressEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateStarButtonState()
            }
        })

        binding.saveServerButton.setOnClickListener {
            val address = binding.addressEditText.text.toString().trim()
            if (address.isBlank()) return@setOnClickListener

            if (NfsPath.isNfs(address)) {
                val nfsPath = NfsPath.parse(address) ?: return@setOnClickListener
                val saved = NetworkConfigStore.getAllServers(this).find {
                    it.isNfs && it.rootUrl().equals(address.trimEnd('/'), ignoreCase = true)
                }
                if (saved != null) {
                    NetworkConfigStore.deleteServer(this, saved.id)
                    SmbCoverImages.remove(this, saved.id)
                    Toast.makeText(
                        this,
                        getString(R.string.smb_server_removed),
                        Toast.LENGTH_SHORT
                    ).show()
                    updateStarButtonState()
                    refreshSavedServers()
                } else {
                    showAddServerDialog(prefillNfsHost = nfsPath.host)
                }
                return@setOnClickListener
            }

            val smbPath = SmbPath.parse(if (address.startsWith("smb://")) address else "smb://$address")
            if (smbPath == null) {
                Toast.makeText(this, getString(R.string.smb_invalid_address), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val allServers = NetworkConfigStore.getAllServers(this)
            val exactConfig = allServers.find {
                !it.isNfs &&
                    it.host.equals(smbPath.host, ignoreCase = true) &&
                it.share.equals(smbPath.share, ignoreCase = true) &&
                it.path.equals(smbPath.path, ignoreCase = true)
            }

            if (exactConfig != null) {
                // Already saved -> Delete it
                NetworkConfigStore.deleteServer(this, exactConfig.id)
                SmbCoverImages.remove(this, exactConfig.id)
                Toast.makeText(this, getString(R.string.smb_server_removed), Toast.LENGTH_SHORT).show()
                updateStarButtonState()
                refreshSavedServers()
            } else {
                // Not saved -> Add it
                val defaultName = smbPath.host + if (smbPath.path.isNotBlank()) " - " + smbPath.path.substringAfterLast('/') else if (smbPath.share.isNotBlank()) " - " + smbPath.share else ""

                // If we already have credentials for this host, reuse them
                val hostConfig = allServers.find {
                    !it.isNfs && it.host.equals(smbPath.host, ignoreCase = true)
                }
                if (hostConfig != null && hostConfig.isAuthenticated) {
                    val newConfig = hostConfig.copy(
                        id = "", // generate new id
                        displayName = defaultName,
                        share = smbPath.share,
                        path = smbPath.path
                    )
                    NetworkConfigStore.addServer(this, newConfig)
                    Toast.makeText(this, getString(R.string.smb_server_saved), Toast.LENGTH_SHORT).show()
                    updateStarButtonState()
                    refreshSavedServers()
                } else {
                    // Prompt for credentials
                    showConnectDialog(smbPath.host, smbPath.share, smbPath.path, defaultName) {
                        Toast.makeText(this, getString(R.string.smb_server_saved), Toast.LENGTH_SHORT).show()
                        updateStarButtonState()
                        refreshSavedServers()
                    }
                }
            }
        }
    }

    private fun updateStarButtonState() {
        val address = binding.addressEditText.text.toString().trim().trimEnd('/')
        if (address.isBlank()) {
            setSaveServerButtonSaved(false)
            return
        }
        val normalized = if (RemotePath.isRemote(address)) address else "smb://$address"
        val isSaved = NetworkConfigStore.getAllServers(this).any {
            it.rootUrl().equals(normalized, ignoreCase = true)
        }
        setSaveServerButtonSaved(isSaved)
    }

    private fun setSaveServerButtonSaved(isSaved: Boolean) {
        binding.saveServerButton.setImageResource(
            if (isSaved) R.drawable.ic_star else R.drawable.ic_star_border,
        )
        val colorAttr = if (isSaved) {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        binding.saveServerButton.imageTintList = ColorStateList.valueOf(
            ThemeColorResolver.resolveColor(this, colorAttr),
        )
    }

    private fun setupRecyclerView() {
        browseAdapter = FolderAdapter(
            onFolderClick = { folder -> onFolderCardClick(folder) },
            onFolderLongClick = { }
        )

        fileListAdapter = RemoteListAdapter { item ->
            if (item.isDirectory) navigateTo(item.url) else openRawFile(item)
        }

        showCardMode()

        // Cards are updated in place when covers and counts arrive: no fade animation.
        (binding.recyclerView.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
    }

    /** A visible list that is about to change its adapter is hidden; the next list shows it again. */
    private fun hideUntilNextList() {
        if (binding.recyclerView.visibility == View.VISIBLE) {
            binding.recyclerView.visibility = View.INVISIBLE
        }
    }

    /** Folders as cards, like on the home screen. */
    private fun showCardMode() {
        if (binding.recyclerView.adapter === browseAdapter) return
        hideUntilNextList()
        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerView.adapter = browseAdapter
    }

    /** Folders and files as a plain list of names (for folders with RAW files). */
    private fun showListMode() {
        if (binding.recyclerView.adapter === fileListAdapter) return
        hideUntilNextList()
        binding.recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerView.adapter = fileListAdapter
    }

    /** Camera RAW files cannot be shown here: open a copy in another app (e.g. Snapseed). */
    private fun openRawFile(file: RemoteFileInfo) {
        ExternalEditFlow.openRemoteInOtherApp(
            this,
            file.url,
            file.name,
            RemoteFileTypes.rawMimeType(file.name),
            Intent.ACTION_VIEW,
            chooser = true
        )
    }

    private fun setupSavedServers() {
        binding.addServerButton.setOnClickListener {
            showAddServerDialog()
        }
        refreshSavedServers()
    }

    private fun refreshSavedServers() {
        updateStarButtonState()
        val servers = NetworkConfigStore.getAllServers(this)
        if (servers.isEmpty() && currentPath.isBlank()) {
            binding.savedServersLayout.visibility = View.VISIBLE
            binding.savedServersList.visibility = View.GONE
            binding.noServersText.visibility = View.VISIBLE
            binding.homeButton.visibility = View.GONE
        } else if (servers.isNotEmpty() && currentPath.isBlank()) {
            binding.savedServersLayout.visibility = View.VISIBLE
            binding.savedServersList.visibility = View.VISIBLE
            binding.noServersText.visibility = View.GONE
            binding.homeButton.visibility = View.GONE
            setupSavedServersList(servers)
        } else {
            binding.savedServersLayout.visibility = View.GONE
            binding.homeButton.visibility = View.VISIBLE
        }
    }

    private fun setupSavedServersList(servers: List<NetworkServerConfig>) {
        binding.savedServersList.removeAllViews()
        for (server in servers) {
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_network_server, binding.savedServersList, false)
            val nameText = itemView.findViewById<TextView>(R.id.serverNameText)
            val hostText = itemView.findViewById<TextView>(R.id.serverHostText)
            val editButton = itemView.findViewById<ImageButton>(R.id.editServerButton)
            val deleteButton = itemView.findViewById<ImageButton>(R.id.deleteServerButton)

            nameText.text = server.displayName.ifBlank { server.host }
            val badge = itemView.findViewById<TextView>(R.id.serverProtocolBadge)
            badge.setText(if (server.isNfs) R.string.protocol_nfs else R.string.protocol_smb)

            if (server.isNfs) {
                hostText.text = "${server.host}${server.export} (uid ${server.uid})"
            } else {
                val sharePart = if (server.share.isNotBlank()) "/${server.share}" else ""
                val pathPart = if (server.path.isNotBlank()) "/${server.path}" else ""
                val connectionInfo = if (server.isGuest) "(Guest)" else "(${server.username})"
                hostText.text = "${server.host}$sharePart$pathPart $connectionInfo"
            }

            itemView.setOnClickListener {
                if (server.isNfs) {
                    binding.addressEditText.setText(server.rootUrl())
                    navigateTo(server.rootUrl())
                } else {
                    val address = if (server.share.isNotBlank()) {
                        val p = if (server.path.isNotBlank()) "/${server.path}" else ""
                        "smb://${server.host}/${server.share}$p"
                    } else {
                        "smb://${server.host}"
                    }
                    binding.addressEditText.setText(address)
                    if (server.share.isNotBlank()) {
                        binding.connectButton.performClick()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.smb_enter_share_name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            editButton.setOnClickListener {
                showEditServerDialog(server)
            }

            deleteButton.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(R.string.smb_delete_server_title)
                    .setMessage(getString(R.string.smb_delete_server_message, server.displayName.ifBlank { server.host }))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        NetworkConfigStore.deleteServer(this, server.id)
                        SmbCoverImages.remove(this, server.id)
                        RemoteClients.forProtocol(server.protocol)?.disconnect(server.host)
                        refreshSavedServers()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            binding.savedServersList.addView(itemView)
        }
    }

    /** Best-effort mapping of an NFS failure to an actionable hint string, or null. */
    private fun nfsErrorHintRes(e: Throwable): Int? {
        var t: Throwable? = e
        while (t != null) {
            val message = t.message ?: ""
            when {
                t is java.net.ConnectException || t is java.net.SocketTimeoutException ||
                    t is java.net.NoRouteToHostException || t is java.net.UnknownHostException ||
                    t is NfsExportLister.ExportListException ->
                    return R.string.nfs_error_unreachable
                message.contains("NFS3ERR_ACCES", ignoreCase = true) ||
                    message.contains("ACCES", ignoreCase = true) ->
                    return R.string.nfs_error_access
                message.contains("mount", ignoreCase = true) ->
                    return R.string.nfs_error_mount_denied
            }
            t = t.cause
        }
        return null
    }

    private fun navigateTo(address: String, restoreScroll: Boolean = false) {
        if (isLoading) return
        val previousPath = currentPath
        summaryJob?.cancel()

        // Remember where the folder we leave was scrolled to. Going back up (or refreshing)
        // returns to that place; entering a folder anew starts at the top.
        rememberScrollPosition(previousPath)
        val scrollTarget = if (restoreScroll || address == previousPath) {
            scrollPositions[address]
        } else {
            scrollPositions.remove(address)
            null
        }

        if (!binding.swipeRefreshLayout.isRefreshing) {
            // The list stays as it is until the new one is ready: no blank flash in between.
            binding.progressBar.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
        }

        isLoading = true

        lifecycleScope.launch {
            try {
                val items = remoteMediaScanner.listDirectory(address)
                val folders = items.filter { it.isDirectory }
                val media = items.filter { !it.isDirectory && it.isMedia }

                if (folders.isEmpty() && media.isNotEmpty()) {
                    // Only pictures and videos: the regular folder screen shows them, exactly
                    // like a local folder.
                    openImagesScreen(address)
                    if (previousPath.isBlank() && launchedWithPath) {
                        finish()
                    } else {
                        showPreviousContent(previousPath)
                    }
                    return@launch
                }

                // Folders with RAW files and no pictures: a plain list of names.
                val rawFiles = items.filter { !it.isDirectory && RemoteFileTypes.isRaw(it.name) }
                if (media.isEmpty() && rawFiles.isNotEmpty()) {
                    showFolder(address)
                    showListMode()
                    binding.emptyView.visibility = View.GONE
                    fileListAdapter.submitList(folders + rawFiles) {
                        applyScrollPosition(scrollTarget)
                        binding.recyclerView.visibility = View.VISIBLE
                    }
                    return@launch
                }

                showFolder(address)
                showCardMode()
                val cards = buildCards(address, folders, media)
                if (cards.isEmpty()) {
                    browseAdapter.submitList(emptyList())
                    binding.emptyView.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    browseAdapter.submitList(cards) {
                        applyScrollPosition(scrollTarget)
                        binding.recyclerView.visibility = View.VISIBLE
                    }
                    loadSummaries(folders)
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkBrowseActivity", "Failed to navigate to $address", e)
                showFolder(address)
                val hintRes = if (NfsPath.isNfs(address)) nfsErrorHintRes(e) else null
                val message = if (hintRes != null) {
                    getString(hintRes)
                } else {
                    getString(R.string.smb_connection_failed, e.message ?: "Unknown error")
                }
                Toast.makeText(this@NetworkBrowseActivity, message, Toast.LENGTH_LONG).show()
                binding.emptyView.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefreshLayout.isRefreshing = false
                isLoading = false
            }
        }
    }

    /** Shows [address] as the current folder (address bar, title, back button). */
    private fun showFolder(address: String) {
        currentPath = address
        binding.addressEditText.setText(address)
        binding.savedServersLayout.visibility = View.GONE
        binding.homeButton.visibility = View.VISIBLE
        // The menu entries (trash, prepare previews) depend on the open folder.
        invalidateOptionsMenu()
        supportActionBar?.title = address.trimEnd('/').substringAfterLast('/')
            .ifBlank { getString(R.string.network_browse_title) }
    }

    /** Brings back what was shown before a folder was handed over to the folder screen. */
    private fun showPreviousContent(previousPath: String) {
        if (previousPath.isBlank()) {
            refreshSavedServers()
            binding.recyclerView.visibility = View.GONE
            return
        }
        val hasCards = (binding.recyclerView.adapter?.itemCount ?: 0) > 0
        binding.recyclerView.visibility = if (hasCards) View.VISIBLE else View.GONE
        binding.emptyView.visibility = if (hasCards) View.GONE else View.VISIBLE
    }

    private fun onFolderCardClick(folder: MediaFolder) {
        if (folder.path.endsWith(IMAGES_CARD_SUFFIX)) {
            openImagesScreen(folder.path.removeSuffix(IMAGES_CARD_SUFFIX))
        } else {
            navigateTo(folder.path)
        }
    }

    /** Opens the regular folder screen (grid, sorting, grouping, selection...) for a network folder. */
    private fun openImagesScreen(folderUrl: String) {
        val serverName = NetworkConfigStore.getAllServers(this)
            .firstOrNull { it.rootUrl() == folderUrl }
            ?.displayName
        val name = serverName?.takeIf { it.isNotBlank() }
            ?: folderUrl.trimEnd('/').substringAfterLast('/')
                .ifBlank { getString(R.string.network_browse_title) }

        startActivity(
            Intent(this, FolderViewActivity::class.java).apply {
                putExtra(FolderViewActivity.EXTRA_FOLDER_PATH, folderUrl)
                putExtra(FolderViewActivity.EXTRA_FOLDER_NAME, name)
            }
        )
    }

    /** Cards for the folder's own pictures (if any) and for each sub folder. */
    private fun buildCards(
        address: String,
        folders: List<RemoteFileInfo>,
        media: List<RemoteFileInfo>
    ): List<MediaFolder> {
        val cards = mutableListOf<MediaFolder>()

        if (media.isNotEmpty()) {
            val pictures = media.filter { !it.isVideo }
            cards.add(
                MediaFolder(
                    name = getString(R.string.network_images_in_folder),
                    path = address + IMAGES_CARD_SUFFIX,
                    itemCount = media.size,
                    thumbnail = coverModel(pictures.maxByOrNull { it.lastModified }?.url),
                    imageCount = pictures.size,
                    videoCount = media.size - pictures.size,
                    totalSizeBytes = media.sumOf { it.size },
                    latestDateModifiedMs = media.maxOf { it.lastModified },
                    isSubFolder = true
                )
            )
        }

        folders.forEach { folder ->
            val card = MediaFolder(
                name = folder.name,
                path = folder.url,
                itemCount = -1,
                isSubFolder = true
            )
            val summary = SmbFolderSummaries.cached(this, folder.url)
            cards.add(if (summary != null) withSummary(card, summary) else card)
        }
        return cards
    }

    private fun withSummary(card: MediaFolder, summary: SmbFolderSummaries.Summary): MediaFolder =
        card.copy(
            itemCount = if (summary.mediaCount > 0) summary.mediaCount else -1,
            thumbnail = coverModel(summary.coverUrl),
            totalSizeBytes = summary.totalSize,
            latestDateModifiedMs = summary.latestModified
        )

    /** Cover pictures come from the thumbnail cache on the share (SMB only). */
    private fun coverModel(pictureUrl: String?): String? =
        if (pictureUrl != null && SmbPath.isSmb(pictureUrl)) "smbthumb://$pictureUrl" else null

    /**
     * Fills in the cover picture, count and size of the folder cards one folder at a time. The
     * results are remembered, so the cards look complete immediately the next time.
     */
    private fun loadSummaries(folders: List<RemoteFileInfo>) {
        if (folders.isEmpty()) return
        summaryJob = lifecycleScope.launch {
            val results = LinkedHashMap<String, SmbFolderSummaries.Summary>()
            var sinceLastUpdate = 0
            // A few folders are read at the same time: much faster than one after the other,
            // without flooding the server.
            val permits = Semaphore(SUMMARY_PARALLEL)
            coroutineScope {
                for (folder in folders) {
                    launch {
                        val summary = permits.withPermit {
                            withContext(Dispatchers.IO) {
                                try {
                                    SmbFolderSummaries.refresh(applicationContext, folder.url)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        } ?: return@launch

                        // Back on the main thread: no other coroutine changes these meanwhile.
                        results[folder.url] = summary
                        sinceLastUpdate++
                        if (sinceLastUpdate >= 3) {
                            applySummaries(results)
                            sinceLastUpdate = 0
                        }
                    }
                }
            }
            applySummaries(results)
        }
    }

    private fun applySummaries(results: Map<String, SmbFolderSummaries.Summary>) {
        if (results.isEmpty()) return
        val updated = browseAdapter.currentList.map { card ->
            val summary = results[card.path]
            if (summary == null) card else withSummary(card, summary)
        }
        browseAdapter.submitList(updated)
    }

    private fun navigateUp() {
        val parent = when {
            SmbPath.isSmb(currentPath) ->
                SmbPath.parse(currentPath)?.let {
                    if (it.path.isBlank()) null else it.parentFolderPath
                }
            NfsPath.isNfs(currentPath) -> nfsParentOrNull(currentPath)
            else -> null
        }
        if (parent == null) goHome() else navigateTo(parent, restoreScroll = true)
    }

    private fun rememberScrollPosition(path: String) {
        if (path.isBlank()) return
        val manager = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val position = manager.findFirstVisibleItemPosition()
        if (position < 0) return
        val offset = manager.findViewByPosition(position)?.top ?: 0
        scrollPositions[path] = intArrayOf(position, offset)
    }

    private fun applyScrollPosition(target: IntArray?) {
        if (target == null) return
        (binding.recyclerView.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(target[0], target[1])
    }

    /** Parent URL of an NFS folder, or null when at (or above) the saved export root. */
    private fun nfsParentOrNull(url: String): String? {
        val nfsPath = NfsPath.parse(url) ?: return null
        val exports = NetworkConfigStore.getAllServers(this)
            .filter { it.isNfs && it.host.equals(nfsPath.host, ignoreCase = true) }
            .map { it.export }
        val export = NfsPath.matchExport(exports, nfsPath.fullPath) ?: return null
        if (nfsPath.fullPath == export) return null
        val parent = nfsPath.fullPath.substringBeforeLast('/', "")
        return if (parent.isEmpty() || NfsPath.matchExport(exports, parent) == null) {
            null
        } else {
            NfsPath.toUrl(nfsPath.host, parent)
        }
    }

    /** Wire the SMB/NFS radio switch and the export-listing button of the connect dialog. */
    private fun bindProtocolSwitch(dialogView: View) {
        val smbRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.protocolSmbRadio)
        val nfsRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.protocolNfsRadio)
        val smbGroup = dialogView.findViewById<View>(R.id.smbFieldsGroup)
        val nfsGroup = dialogView.findViewById<View>(R.id.nfsFieldsGroup)
        val advancedCheck =
            dialogView.findViewById<android.widget.CheckBox>(R.id.nfsAdvancedCheckBox)
        val advancedGroup = dialogView.findViewById<View>(R.id.nfsAdvancedGroup)
        val hostEdit = dialogView.findViewById<EditText>(R.id.hostEditText)
        val exportEdit =
            dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.exportEditText)
        val listExportsButton =
            dialogView.findViewById<android.widget.Button>(R.id.listExportsButton)

        val applyProtocol = {
            val nfs = nfsRadio.isChecked
            smbGroup.visibility = if (nfs) View.GONE else View.VISIBLE
            nfsGroup.visibility = if (nfs) View.VISIBLE else View.GONE
        }
        smbRadio.setOnCheckedChangeListener { _, _ -> applyProtocol() }
        nfsRadio.setOnCheckedChangeListener { _, _ -> applyProtocol() }
        applyProtocol()

        advancedCheck.setOnCheckedChangeListener { _, isChecked ->
            advancedGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        listExportsButton.setOnClickListener {
            val host = hostEdit.text.toString().trim()
                .removePrefix("nfs://")
                .substringBefore('/')
            if (host.isBlank()) {
                Toast.makeText(this, R.string.smb_enter_host, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            listExportsButton.isEnabled = false
            lifecycleScope.launch {
                try {
                    val exports =
                        withContext(Dispatchers.IO) { NfsExportLister.listExports(host) }
                    if (exports.isEmpty()) {
                        Toast.makeText(
                            this@NetworkBrowseActivity,
                            R.string.nfs_no_exports_found,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        exportEdit.setAdapter(
                            android.widget.ArrayAdapter(
                                this@NetworkBrowseActivity,
                                android.R.layout.simple_dropdown_item_1line,
                                exports
                            )
                        )
                        exportEdit.showDropDown()
                    }
                } catch (e: Exception) {
                    android.util.Log.e(
                        "NetworkBrowseActivity",
                        "Export listing failed for $host",
                        e
                    )
                    Toast.makeText(
                        this@NetworkBrowseActivity,
                        getString(R.string.smb_connection_failed, e.message ?: "Unknown"),
                        Toast.LENGTH_SHORT
                    ).show()
                } finally {
                    listExportsButton.isEnabled = true
                }
            }
        }
    }

    /**
     * Read a NetworkServerConfig from the dialog fields, or null (after showing a
     * toast) when required fields are missing. [existingId] is empty for new servers.
     */
    private fun readConfigFromDialog(
        dialogView: View,
        existingId: String
    ): NetworkServerConfig? {
        val nfsSelected =
            dialogView.findViewById<android.widget.RadioButton>(R.id.protocolNfsRadio).isChecked
        val hostInput =
            dialogView.findViewById<EditText>(R.id.hostEditText).text.toString().trim()
        val displayName =
            dialogView.findViewById<EditText>(R.id.displayNameEditText).text.toString().trim()

        if (hostInput.isBlank()) {
            Toast.makeText(this, R.string.smb_enter_host, Toast.LENGTH_SHORT).show()
            return null
        }

        if (nfsSelected) {
            val export =
                dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.exportEditText)
                    .text.toString().trim().trimEnd('/')
            if (export.isBlank() || !export.startsWith("/")) {
                Toast.makeText(this, R.string.nfs_enter_export, Toast.LENGTH_SHORT).show()
                return null
            }
            val host = hostInput.removePrefix("nfs://").substringBefore('/')
            return NetworkServerConfig(
                id = existingId,
                displayName = displayName.ifBlank { host },
                host = host,
                protocol = NetworkServerConfig.PROTOCOL_NFS,
                export = export,
                uid = dialogView.findViewById<EditText>(R.id.uidEditText)
                    .text.toString().toIntOrNull() ?: 0,
                gid = dialogView.findViewById<EditText>(R.id.gidEditText)
                    .text.toString().toIntOrNull() ?: 0
            )
        }

        var parsedHost = hostInput
        var parsedShare = ""
        var parsedPath = ""
        val smbPath =
            SmbPath.parse(if (hostInput.startsWith("smb://")) hostInput else "smb://$hostInput")
        if (smbPath != null) {
            parsedHost = smbPath.host
            parsedShare = smbPath.share
            parsedPath = smbPath.path
        } else {
            val normalized =
                if (hostInput.startsWith("smb://")) hostInput else "smb://$hostInput"
            parsedHost = normalized.removePrefix("smb://").substringBefore("/")
        }
        return NetworkServerConfig(
            id = existingId,
            displayName = displayName.ifBlank { parsedHost },
            host = parsedHost,
            protocol = NetworkServerConfig.PROTOCOL_SMB,
            share = parsedShare,
            path = parsedPath,
            username = dialogView.findViewById<EditText>(R.id.usernameEditText)
                .text.toString().trim(),
            password = dialogView.findViewById<EditText>(R.id.passwordEditText)
                .text.toString(),
            isGuest =
                dialogView.findViewById<android.widget.CheckBox>(R.id.guestCheckBox).isChecked
        )
    }

    /** Save-with-test shared by add and edit. Runs testConnection for the config's protocol. */
    private fun testAndSaveServer(
        config: NetworkServerConfig,
        isNew: Boolean,
        onSuccess: () -> Unit
    ) {
        val client = RemoteClients.forProtocol(config.protocol)
        if (client == null) {
            Toast.makeText(this, R.string.smb_connection_test_failed, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val success = withContext(Dispatchers.IO) { client.testConnection(config) }
                if (success) {
                    if (isNew) {
                        NetworkConfigStore.addServer(this@NetworkBrowseActivity, config)
                    } else {
                        NetworkConfigStore.updateServer(this@NetworkBrowseActivity, config)
                    }
                    refreshSavedServers()
                    Toast.makeText(
                        this@NetworkBrowseActivity,
                        R.string.smb_server_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                    onSuccess()
                } else {
                    val hint = if (config.isNfs) {
                        R.string.nfs_error_mount_denied
                    } else {
                        R.string.smb_connection_test_failed
                    }
                    Toast.makeText(this@NetworkBrowseActivity, hint, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@NetworkBrowseActivity,
                    getString(R.string.smb_connection_failed, e.message ?: "Unknown"),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /** SMB credential prompt used when navigating to an unknown SMB host from the address bar. */
    private fun showConnectDialog(
        host: String,
        share: String,
        path: String = "",
        defaultName: String = host,
        onSuccess: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_network_connect, null)
        // This prompt is SMB-only: hide the protocol switch and keep SMB fields.
        dialogView.findViewById<View>(R.id.protocolGroup).visibility = View.GONE
        val usernameEdit = dialogView.findViewById<EditText>(R.id.usernameEditText)
        val passwordEdit = dialogView.findViewById<EditText>(R.id.passwordEditText)
        val displayNameEdit = dialogView.findViewById<EditText>(R.id.displayNameEditText)
        val guestCheckBox = dialogView.findViewById<android.widget.CheckBox>(R.id.guestCheckBox)

        displayNameEdit.setText(defaultName)

        guestCheckBox.setOnCheckedChangeListener { _, isChecked ->
            usernameEdit.isEnabled = !isChecked
            passwordEdit.isEnabled = !isChecked
            if (isChecked) {
                usernameEdit.setText("")
                passwordEdit.setText("")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.smb_connect_to, host))
            .setView(dialogView)
            .setPositiveButton(R.string.smb_connect) { _, _ ->
                val config = NetworkServerConfig(
                    id = "",
                    displayName = displayNameEdit.text.toString().trim(),
                    host = host,
                    share = share,
                    path = path,
                    username = usernameEdit.text.toString().trim(),
                    password = passwordEdit.text.toString(),
                    isGuest = guestCheckBox.isChecked
                )
                testAndSaveServer(config, isNew = true, onSuccess = onSuccess)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditServerDialog(server: NetworkServerConfig) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_network_connect, null)
        val hostEdit = dialogView.findViewById<EditText>(R.id.hostEditText)
        val displayNameEdit = dialogView.findViewById<EditText>(R.id.displayNameEditText)
        hostEdit.visibility = View.VISIBLE
        bindProtocolSwitch(dialogView)

        val smbRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.protocolSmbRadio)
        val nfsRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.protocolNfsRadio)
        // The protocol of a saved server is fixed; hide the switch when editing.
        dialogView.findViewById<View>(R.id.protocolGroup).visibility = View.GONE
        if (server.isNfs) nfsRadio.isChecked = true else smbRadio.isChecked = true

        displayNameEdit.setText(server.displayName)
        if (server.isNfs) {
            hostEdit.setText(server.host)
            dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.exportEditText)
                .setText(server.export)
            dialogView.findViewById<EditText>(R.id.uidEditText).setText(server.uid.toString())
            dialogView.findViewById<EditText>(R.id.gidEditText).setText(server.gid.toString())
            if (server.uid != 0 || server.gid != 0) {
                dialogView.findViewById<android.widget.CheckBox>(R.id.nfsAdvancedCheckBox)
                    .isChecked = true
            }
        } else {
            val fullAddress = if (server.share.isNotBlank()) {
                val p = if (server.path.isNotBlank()) "/${server.path}" else ""
                "${server.host}/${server.share}$p"
            } else {
                server.host
            }
            hostEdit.setText(fullAddress)
            val usernameEdit = dialogView.findViewById<EditText>(R.id.usernameEditText)
            val passwordEdit = dialogView.findViewById<EditText>(R.id.passwordEditText)
            val guestCheckBox =
                dialogView.findViewById<android.widget.CheckBox>(R.id.guestCheckBox)
            usernameEdit.setText(server.username)
            passwordEdit.setText(server.password)
            guestCheckBox.isChecked = server.isGuest
            usernameEdit.isEnabled = !server.isGuest
            passwordEdit.isEnabled = !server.isGuest
            guestCheckBox.setOnCheckedChangeListener { _, isChecked ->
                usernameEdit.isEnabled = !isChecked
                passwordEdit.isEnabled = !isChecked
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.smb_edit_server)
            .setView(dialogView)
            .setPositiveButton(R.string.smb_save) { _, _ ->
                val config = readConfigFromDialog(dialogView, existingId = server.id)
                    ?: return@setPositiveButton
                testAndSaveServer(config, isNew = false) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddServerDialog(prefillNfsHost: String? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_network_connect, null)
        val hostEdit = dialogView.findViewById<EditText>(R.id.hostEditText)
        hostEdit.visibility = View.VISIBLE
        bindProtocolSwitch(dialogView)

        val guestCheckBox = dialogView.findViewById<android.widget.CheckBox>(R.id.guestCheckBox)
        val usernameEdit = dialogView.findViewById<EditText>(R.id.usernameEditText)
        val passwordEdit = dialogView.findViewById<EditText>(R.id.passwordEditText)
        guestCheckBox.setOnCheckedChangeListener { _, isChecked ->
            usernameEdit.isEnabled = !isChecked
            passwordEdit.isEnabled = !isChecked
        }

        if (prefillNfsHost != null) {
            dialogView.findViewById<android.widget.RadioButton>(R.id.protocolNfsRadio)
                .isChecked = true
            hostEdit.setText(prefillNfsHost)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.smb_add_server)
            .setView(dialogView)
            .setPositiveButton(R.string.smb_save) { _, _ ->
                val config = readConfigFromDialog(dialogView, existingId = "")
                    ?: return@setPositiveButton
                testAndSaveServer(config, isNew = true) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/**
 * RecyclerView adapter for network (SMB/NFS) browse file listing.
 */
