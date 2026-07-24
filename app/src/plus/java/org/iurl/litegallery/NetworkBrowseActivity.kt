package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iurl.litegallery.databinding.ActivityNetworkBrowseBinding
import org.iurl.litegallery.theme.ThemeColorResolver
import org.iurl.litegallery.theme.ThemeVariant

/**
 * Activity for browsing network shares (SMB and NFS). Provides an address bar for manual
 * entry, saved-server bookmarks, and a file list for navigation.
 */
class NetworkBrowseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_PATH = "extra_start_path"
    }

    private lateinit var binding: ActivityNetworkBrowseBinding
    private lateinit var browseAdapter: NetworkBrowseAdapter
    private lateinit var remoteMediaScanner: RemoteMediaScanner
    private var currentPath: String = ""
    private var isLoading = false
    private var currentPackKey: String? = null

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

        setupToolbar()
        setupAddressBar()
        setupRecyclerView()
        setupSavedServers()
        setupSwipeRefresh()
        ThemeHelper.applyRuntimeCustomColors(this)

        // Handle intent
        val intentPath = intent.getStringExtra(EXTRA_START_PATH)
        if (!intentPath.isNullOrBlank()) {
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        if (currentPath.isNotBlank()) {
            navigateUp()
        } else {
            super.onBackPressed()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.smb_browse_title)
        }
        
        binding.homeButton.setOnClickListener {
            goHome()
        }
    }

    private fun goHome() {
        currentPath = ""
        refreshSavedServers()
        browseAdapter.submitList(emptyList())
        binding.recyclerView.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        binding.addressEditText.setText("")
        supportActionBar?.title = getString(R.string.smb_browse_title)
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

            // Normalize address
            val smbAddress = if (address.startsWith("smb://")) {
                address
            } else if (address.startsWith("\\\\") || address.startsWith("//")) {
                "smb://${address.removePrefix("\\\\").removePrefix("//").replace('\\', '/')}"
            } else {
                "smb://$address"
            }
            
            if (binding.addressEditText.text.toString() != smbAddress) {
                binding.addressEditText.setText(smbAddress)
                binding.addressEditText.setSelection(smbAddress.length)
            }

            // Check if we need credentials
            val smbPath = SmbPath.parse(smbAddress)
            if (smbPath == null) {
                Toast.makeText(this, getString(R.string.smb_invalid_address), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check if we have saved credentials for this exact path
            val allConfigs = NetworkConfigStore.getAllServers(this)
            val exactConfig = allConfigs.find {
                it.host.equals(smbPath.host, ignoreCase = true) &&
                it.share.equals(smbPath.share, ignoreCase = true) &&
                it.path.equals(smbPath.path, ignoreCase = true)
            }
            if (exactConfig != null) {
                navigateTo(smbAddress)
                return@setOnClickListener
            }

            // Otherwise, check if we have ANY credentials for this host
            val hostConfig = NetworkConfigStore.getServerByHost(this, smbPath.host)
            if (hostConfig != null) {
                navigateTo(smbAddress)
            } else {
                val defaultName = smbPath.host + if (smbPath.path.isNotBlank()) " - " + smbPath.path.substringAfterLast('/') else if (smbPath.share.isNotBlank()) " - " + smbPath.share else ""
                showConnectDialog(smbPath.host, smbPath.share, smbPath.path, defaultName) {
                    navigateTo(smbAddress)
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

            val smbPath = SmbPath.parse(if (address.startsWith("smb://")) address else "smb://$address")
            if (smbPath == null) {
                Toast.makeText(this, getString(R.string.smb_invalid_address), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val allServers = NetworkConfigStore.getAllServers(this)
            val exactConfig = allServers.find {
                it.host.equals(smbPath.host, ignoreCase = true) &&
                it.share.equals(smbPath.share, ignoreCase = true) &&
                it.path.equals(smbPath.path, ignoreCase = true)
            }

            if (exactConfig != null) {
                // Already saved -> Delete it
                NetworkConfigStore.deleteServer(this, exactConfig.id)
                Toast.makeText(this, getString(R.string.smb_server_removed), Toast.LENGTH_SHORT).show()
                updateStarButtonState()
                refreshSavedServers()
            } else {
                // Not saved -> Add it
                val defaultName = smbPath.host + if (smbPath.path.isNotBlank()) " - " + smbPath.path.substringAfterLast('/') else if (smbPath.share.isNotBlank()) " - " + smbPath.share else ""
                
                // If we already have credentials for this host, reuse them
                val hostConfig = NetworkConfigStore.getServerByHost(this, smbPath.host)
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
        val address = binding.addressEditText.text.toString().trim()
        if (address.isBlank()) {
            setSaveServerButtonSaved(false)
            return
        }

        val smbPath = SmbPath.parse(if (address.startsWith("smb://")) address else "smb://$address")
        if (smbPath == null) {
            setSaveServerButtonSaved(false)
            return
        }

        val allServers = NetworkConfigStore.getAllServers(this)
        val isSaved = allServers.any {
            it.host.equals(smbPath.host, ignoreCase = true) &&
            it.share.equals(smbPath.share, ignoreCase = true) &&
            it.path.equals(smbPath.path, ignoreCase = true)
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
        browseAdapter = NetworkBrowseAdapter(
            onItemClick = { item ->
                if (item.isDirectory) {
                    navigateTo(item.url)
                } else if (item.isMedia) {
                    // Open media viewer with full folder context for swipe support
                    openMediaViewer(item)
                }
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@NetworkBrowseActivity)
            adapter = browseAdapter
        }
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
            val sharePart = if (server.share.isNotBlank()) "/${server.share}" else ""
            val pathPart = if (server.path.isNotBlank()) "/${server.path}" else ""
            val connectionInfo = if (server.isGuest) "(Guest)" else "(${server.username})"
            hostText.text = "${server.host}$sharePart$pathPart $connectionInfo"

            itemView.setOnClickListener {
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
                    Toast.makeText(this, getString(R.string.smb_enter_share_name), Toast.LENGTH_SHORT).show()
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
                        SmbClient.disconnect(server.host)
                        refreshSavedServers()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            binding.savedServersList.addView(itemView)
        }
    }

    private fun navigateTo(smbAddress: String) {
        if (isLoading) return

        currentPath = smbAddress
        binding.addressEditText.setText(smbAddress)
        binding.savedServersLayout.visibility = View.GONE
        binding.homeButton.visibility = View.VISIBLE
        
        if (!binding.swipeRefreshLayout.isRefreshing) {
            binding.progressBar.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.GONE
        }
        
        isLoading = true

        // Update toolbar title
        val smbPath = SmbPath.parse(smbAddress)
        supportActionBar?.title = smbPath?.let {
            if (it.path.isBlank()) it.share else it.path.substringAfterLast('/')
        } ?: getString(R.string.smb_browse_title)

        lifecycleScope.launch {
            try {
                val items = remoteMediaScanner.listDirectory(smbAddress)

                if (items.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    browseAdapter.submitList(items)
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.emptyView.visibility = View.GONE
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkBrowseActivity", "Failed to navigate to $smbAddress", e)
                Toast.makeText(
                    this@NetworkBrowseActivity,
                    getString(R.string.smb_connection_failed, e.message ?: "Unknown error"),
                    Toast.LENGTH_LONG
                ).show()
                binding.emptyView.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefreshLayout.isRefreshing = false
                isLoading = false
            }
        }
    }

    private fun navigateUp() {
        val smbPath = SmbPath.parse(currentPath) ?: run {
            finish()
            return
        }

        if (smbPath.path.isBlank()) {
            // At share root, go back to saved servers
            goHome()
        } else {
            navigateTo(smbPath.parentFolderPath)
        }
    }

    private fun openMediaViewer(item: RemoteFileInfo) {
        // Open the media viewer directly on the clicked file. The viewer scans the SMB
        // folder through MediaSourceRegistry/NetworkMediaSource for swipe context and positions
        // itself to the target path, so tapping a file lands on that exact photo/video.
        val targetUrl = item.url
        val intent = Intent(this, MediaViewerActivity::class.java).apply {
            putExtra(MediaViewerActivity.EXTRA_FOLDER_PATH, currentPath)
            putExtra(MediaViewerActivity.EXTRA_MEDIA_PATH, targetUrl)
        }
        startActivity(intent)
    }

    private fun showConnectDialog(host: String, share: String, path: String = "", defaultName: String = host, onSuccess: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_smb_connect, null)
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

                // Save and connect
                lifecycleScope.launch {
                    binding.progressBar.visibility = View.VISIBLE
                    try {
                        val success = withContext(Dispatchers.IO) {
                            SmbClient.testConnection(config)
                        }
                        if (success) {
                            NetworkConfigStore.addServer(this@NetworkBrowseActivity, config)
                            refreshSavedServers()
                            onSuccess()
                        } else {
                            Toast.makeText(
                                this@NetworkBrowseActivity,
                                R.string.smb_connection_test_failed,
                                Toast.LENGTH_SHORT
                            ).show()
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
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditServerDialog(server: NetworkServerConfig) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_smb_connect, null)
        val usernameEdit = dialogView.findViewById<EditText>(R.id.usernameEditText)
        val passwordEdit = dialogView.findViewById<EditText>(R.id.passwordEditText)
        val displayNameEdit = dialogView.findViewById<EditText>(R.id.displayNameEditText)
        val guestCheckBox = dialogView.findViewById<android.widget.CheckBox>(R.id.guestCheckBox)
        val hostEdit = dialogView.findViewById<EditText>(R.id.hostEditText)
        hostEdit.visibility = View.VISIBLE

        val fullAddress = if (server.share.isNotBlank()) {
            val p = if (server.path.isNotBlank()) "/${server.path}" else ""
            "${server.host}/${server.share}$p"
        } else server.host
        hostEdit.setText(fullAddress)
        displayNameEdit.setText(server.displayName)
        usernameEdit.setText(server.username)
        passwordEdit.setText(server.password)
        guestCheckBox.isChecked = server.isGuest

        // Initial state
        usernameEdit.isEnabled = !server.isGuest
        passwordEdit.isEnabled = !server.isGuest

        guestCheckBox.setOnCheckedChangeListener { _, isChecked ->
            usernameEdit.isEnabled = !isChecked
            passwordEdit.isEnabled = !isChecked
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.smb_edit_server)
            .setView(dialogView)
            .setPositiveButton(R.string.smb_save) { _, _ ->
                val hostInput = hostEdit.text.toString().trim()
                if (hostInput.isBlank()) {
                    Toast.makeText(this, R.string.smb_enter_host, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                var parsedHost = hostInput
                var parsedShare = ""
                var parsedPath = ""
                
                val smbPath = SmbPath.parse(if (hostInput.startsWith("smb://")) hostInput else "smb://$hostInput")
                if (smbPath != null) {
                    parsedHost = smbPath.host
                    parsedShare = smbPath.share
                    parsedPath = smbPath.path
                } else {
                    val normalized = if (hostInput.startsWith("smb://")) hostInput else "smb://$hostInput"
                    parsedHost = normalized.removePrefix("smb://").substringBefore("/")
                }

                val config = NetworkServerConfig(
                    id = server.id,
                    displayName = displayNameEdit.text.toString().trim().ifBlank { parsedHost },
                    host = parsedHost,
                    share = parsedShare,
                    path = parsedPath,
                    username = usernameEdit.text.toString().trim(),
                    password = passwordEdit.text.toString(),
                    isGuest = guestCheckBox.isChecked
                )

                lifecycleScope.launch {
                    binding.progressBar.visibility = View.VISIBLE
                    try {
                        val success = withContext(Dispatchers.IO) {
                            SmbClient.testConnection(config)
                        }
                        if (success) {
                            NetworkConfigStore.updateServer(this@NetworkBrowseActivity, config)
                            refreshSavedServers()
                            Toast.makeText(
                                this@NetworkBrowseActivity,
                                R.string.smb_server_saved,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@NetworkBrowseActivity,
                                R.string.smb_connection_test_failed,
                                Toast.LENGTH_SHORT
                            ).show()
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
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddServerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_smb_connect, null)
        val usernameEdit = dialogView.findViewById<EditText>(R.id.usernameEditText)
        val passwordEdit = dialogView.findViewById<EditText>(R.id.passwordEditText)
        val displayNameEdit = dialogView.findViewById<EditText>(R.id.displayNameEditText)
        val guestCheckBox = dialogView.findViewById<android.widget.CheckBox>(R.id.guestCheckBox)
        val hostEdit = dialogView.findViewById<EditText>(R.id.hostEditText)
        hostEdit.visibility = View.VISIBLE

        guestCheckBox.setOnCheckedChangeListener { _, isChecked ->
            usernameEdit.isEnabled = !isChecked
            passwordEdit.isEnabled = !isChecked
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.smb_add_server)
            .setView(dialogView)
            .setPositiveButton(R.string.smb_save) { _, _ ->
                val hostInput = hostEdit.text.toString().trim()
                if (hostInput.isBlank()) {
                    Toast.makeText(this, R.string.smb_enter_host, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                var parsedHost = hostInput
                var parsedShare = ""
                var parsedPath = ""
                
                val smbPath = SmbPath.parse(if (hostInput.startsWith("smb://")) hostInput else "smb://$hostInput")
                if (smbPath != null) {
                    parsedHost = smbPath.host
                    parsedShare = smbPath.share
                    parsedPath = smbPath.path
                } else {
                    val normalized = if (hostInput.startsWith("smb://")) hostInput else "smb://$hostInput"
                    parsedHost = normalized.removePrefix("smb://").substringBefore("/")
                }

                val config = NetworkServerConfig(
                    id = "",
                    displayName = displayNameEdit.text.toString().trim().ifBlank { parsedHost },
                    host = parsedHost,
                    share = parsedShare,
                    path = parsedPath,
                    username = usernameEdit.text.toString().trim(),
                    password = passwordEdit.text.toString(),
                    isGuest = guestCheckBox.isChecked
                )

                lifecycleScope.launch {
                    binding.progressBar.visibility = View.VISIBLE
                    try {
                        val success = withContext(Dispatchers.IO) {
                            SmbClient.testConnection(config)
                        }
                        if (success) {
                            NetworkConfigStore.addServer(this@NetworkBrowseActivity, config)
                            refreshSavedServers()
                            Toast.makeText(
                                this@NetworkBrowseActivity,
                                R.string.smb_server_saved,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@NetworkBrowseActivity,
                                R.string.smb_connection_test_failed,
                                Toast.LENGTH_SHORT
                            ).show()
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
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/**
 * RecyclerView adapter for SMB browse file listing.
 */
class NetworkBrowseAdapter(
    private val onItemClick: (RemoteFileInfo) -> Unit
) : ListAdapter<RemoteFileInfo, NetworkBrowseAdapter.RemoteFileViewHolder>(RemoteFileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RemoteFileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_browse, parent, false)
        return RemoteFileViewHolder(view)
    }

    override fun onBindViewHolder(holder: RemoteFileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RemoteFileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: android.widget.ImageView = itemView.findViewById(R.id.fileIcon)
        private val nameText: TextView = itemView.findViewById(R.id.fileNameText)
        private val sizeText: TextView = itemView.findViewById(R.id.fileSizeText)
        private val thumbnailView: android.widget.ImageView = itemView.findViewById(R.id.thumbnailView)
        private val neutralIconTint = ColorStateList.valueOf(
            ThemeColorResolver.resolveColor(itemView.context, com.google.android.material.R.attr.colorOnSurfaceVariant),
        )

        fun bind(item: RemoteFileInfo) {
            nameText.text = item.name

            if (item.isDirectory) {
                iconView.setImageResource(R.drawable.ic_folder)
                iconView.imageTintList = neutralIconTint
                iconView.visibility = View.VISIBLE
                thumbnailView.visibility = View.GONE
                sizeText.text = ""
            } else {
                iconView.visibility = View.GONE

                if (item.isMedia) {
                    // Show thumbnail for media files
                    thumbnailView.visibility = View.VISIBLE
                    val model = item.url
                    val context = itemView.context
                    if (context is android.app.Activity && !context.isDestroyed && !context.isFinishing) {
                        Glide.with(context)
                            .load(model)
                            .centerCrop()
                            .placeholder(R.drawable.ic_image_placeholder)
                            .error(R.drawable.ic_image_placeholder)
                            .into(thumbnailView)
                    }
                } else {
                    thumbnailView.visibility = View.GONE
                    iconView.visibility = View.VISIBLE
                    iconView.setImageResource(R.drawable.ic_image_placeholder)
                    iconView.imageTintList = neutralIconTint
                }

                // Format file size
                sizeText.text = formatSize(item.size)
            }

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return ""
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1 -> String.format("%.2f GB", gb)
                mb >= 1 -> String.format("%.1f MB", mb)
                kb >= 1 -> String.format("%.0f KB", kb)
                else -> "$bytes B"
            }
        }
    }

    private class RemoteFileDiffCallback : DiffUtil.ItemCallback<RemoteFileInfo>() {
        override fun areItemsTheSame(oldItem: RemoteFileInfo, newItem: RemoteFileInfo): Boolean =
            oldItem.url == newItem.url && oldItem.isDirectory == newItem.isDirectory

        override fun areContentsTheSame(oldItem: RemoteFileInfo, newItem: RemoteFileInfo): Boolean =
            oldItem == newItem
    }
}
