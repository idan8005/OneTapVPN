package com.example.onetapvpn

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var routeText: TextView
    private lateinit var serverSummaryText: TextView
    private lateinit var serverSelectButton: MaterialButton
    private lateinit var serverRefreshButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var updateButton: MaterialButton
    private lateinit var updateText: TextView
    private lateinit var updateProgress: ProgressBar

    private val vpnManager by lazy { IranVpnManager(applicationContext) }
    private val profileStore by lazy { IranVpnProfileStore(applicationContext) }
    private val discovery by lazy { IranServerDiscovery() }
    private val routingPreferences by lazy { RoutingPreferences(applicationContext) }
    private val updater by lazy { AppUpdater(applicationContext) }

    private var routing = RoutingSettings()
    private var serverProfiles: List<IranVpnProfile> = emptyList()
    private var selectedServerKey: String = IranVpnProfileStore.AUTO_SELECTION_KEY
    private var activeServerTag: String? = null
    private var serverNotice: String? = null
    private var uiState = UiState.IDLE
    private var connectionRequested = false
    private var connectionMessage = ""
    private var discoveryJob: Job? = null
    private var serverRefreshJob: Job? = null
    private var updateJob: Job? = null
    private var pendingInstallFile: File? = null
    private var pendingProfile: IranVpnProfile? = null
    private var receiverRegistered = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!connectionRequested) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            startTunnel()
        } else {
            failConnection(getString(R.string.vpn_permission_denied))
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != IranVpnContract.ACTION_STATUS) return
            val state = intent.getStringExtra(IranVpnContract.EXTRA_STATE).orEmpty()
            val message = intent.getStringExtra(IranVpnContract.EXTRA_MESSAGE).orEmpty()
            intent.getStringExtra(IranVpnContract.EXTRA_ACTIVE_TAG)
                ?.takeIf { it.isNotBlank() }
                ?.let { activeServerTag = it }

            if (state == IranVpnContract.STATE_SERVER_HEALTH) {
                updateServerHealth(
                    intent.getStringExtra(IranVpnContract.EXTRA_SERVER_TAG).orEmpty(),
                    intent.getIntExtra(IranVpnContract.EXTRA_PING_MS, -1),
                    intent.getDoubleExtra(IranVpnContract.EXTRA_SPEED_MBPS, -1.0),
                    intent.getStringExtra(IranVpnContract.EXTRA_HEALTH_STATUS).orEmpty()
                )
                return
            }

            when (state) {
                IranVpnContract.STATE_CONNECTED -> {
                    if (!connectionRequested) {
                        vpnManager.stop()
                        return
                    }
                    uiState = UiState.CONNECTED
                    connectionMessage = getString(R.string.iran_connected)
                    render()
                }
                IranVpnContract.STATE_ERROR -> {
                    if (connectionRequested) {
                        failConnection(message.ifBlank { getString(R.string.status_error) })
                    }
                }
                IranVpnContract.STATE_DISCONNECTED -> {
                    if (connectionRequested && uiState == UiState.CONNECTED) {
                        failConnection(getString(R.string.iran_tunnel_lost))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 18, 31)
        window.navigationBarColor = Color.rgb(11, 18, 31)
        window.decorView.systemUiVisibility = 0

        routing = routingPreferences.read()
        serverProfiles = profileStore.readServers()
        selectedServerKey = profileStore.readSelectionKey()
        reconcileSelectionAfterRefresh()
        setContentView(createContent())
        registerStatusReceiver()
        updateCachedProfileSummary()
        render()
        if (serverProfiles.isEmpty()) refreshServers()
    }

    override fun onResume() {
        super.onResume()
        val file = pendingInstallFile
        if (file != null && canInstallPackages()) {
            installDownloadedUpdate(file)
        } else if (file == null) {
            resumePendingUpdateDownload()
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(statusReceiver)
        discoveryJob?.cancel()
        serverRefreshJob?.cancel()
        updateJob?.cancel()
        super.onDestroy()
    }

    private fun createContent(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 18, 31))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(30), dp(22), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_raahat_vpn)
                contentDescription = getString(R.string.app_name)
            },
            LinearLayout.LayoutParams(dp(54), dp(54))
        )
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label(getString(R.string.app_name), 23f, Color.WHITE, Typeface.BOLD))
        brand.addView(label(getString(R.string.app_tagline), 13f, Color.rgb(158, 177, 204)))
        brand.addView(
            label(getString(R.string.app_version, BuildConfig.VERSION_NAME), 11f, Color.rgb(122, 146, 178))
        )
        header.addView(
            brand,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(13)
            }
        )
        content.addView(header, matchWrap())

        val serverCard = card(Color.rgb(19, 32, 53), Color.rgb(43, 64, 92))
        val serverPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        serverPanel.addView(
            label(getString(R.string.server_selection_title), 14f, Color.WHITE, Typeface.BOLD)
        )
        serverSummaryText = label("", 13f, Color.rgb(164, 184, 211)).apply {
            setPadding(0, dp(8), 0, dp(10))
        }
        serverPanel.addView(serverSummaryText, matchWrap())
        val serverActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        serverSelectButton = outlinedButton(getString(R.string.server_choose_button)) {
            openServerPicker()
        }
        serverActions.addView(serverSelectButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginEnd = dp(6)
        })
        serverRefreshButton = outlinedButton(getString(R.string.server_refresh_button)) {
            refreshServers()
        }
        serverActions.addView(serverRefreshButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginStart = dp(6)
        })
        serverPanel.addView(serverActions, matchWrap())
        serverCard.addView(serverPanel)
        content.addView(serverCard, matchWrap().apply { topMargin = dp(22) })

        val connectionCard = card(Color.rgb(19, 32, 53), Color.rgb(43, 64, 92))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(26), dp(18), dp(22))
        }
        panel.addView(
            label(getString(R.string.connection_title), 13f, Color.rgb(190, 210, 235), Typeface.BOLD)
        )
        statusText = label(getString(R.string.status_ready), 25f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
        }
        panel.addView(statusText, matchWrap().apply { topMargin = dp(10) })

        val connectProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            tag = CONNECT_PROGRESS_TAG
        }
        panel.addView(connectProgress, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            topMargin = dp(10)
        })

        connectButton = MaterialButton(this).apply {
            text = getString(R.string.connect)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            cornerRadius = dp(100)
            insetTop = dp(4)
            insetBottom = dp(4)
            setOnClickListener { onConnectionButtonPressed() }
        }
        panel.addView(connectButton, LinearLayout.LayoutParams(dp(168), dp(168)).apply {
            topMargin = dp(16)
            bottomMargin = dp(14)
        })

        detailText = label(getString(R.string.connection_help), 13f, Color.rgb(164, 184, 211)).apply {
            gravity = Gravity.CENTER
        }
        panel.addView(detailText, matchWrap())
        connectionCard.addView(panel)
        content.addView(connectionCard, matchWrap().apply { topMargin = dp(28) })

        renderServerSelection()
        routeText = label("", 12f, Color.rgb(145, 166, 195)).apply {
            gravity = Gravity.CENTER
        }
        content.addView(routeText, matchWrap().apply {
            topMargin = dp(12)
            bottomMargin = dp(16)
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val settingsButton = outlinedButton(getString(R.string.settings_button)) {
            openSettings()
        }
        actionRow.addView(settingsButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(6)
        })
        updateButton = outlinedButton(getString(R.string.update_button)) {
            checkForUpdates()
        }
        actionRow.addView(updateButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(6)
        })
        content.addView(actionRow, matchWrap())

        updateText = label("", 12f, Color.rgb(170, 190, 216)).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
        }
        content.addView(updateText, matchWrap().apply { topMargin = dp(12) })
        updateProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        content.addView(updateProgress, matchWrap().apply { topMargin = dp(8) })

        content.addView(
            label(getString(R.string.privacy_short), 11f, Color.rgb(130, 149, 178)).apply {
                gravity = Gravity.CENTER
            },
            matchWrap().apply {
                topMargin = dp(22)
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        )
        return scroll
    }

    private fun registerStatusReceiver() {
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(IranVpnContract.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun onConnectionButtonPressed() {
        when (uiState) {
            UiState.DISCOVERING, UiState.CONNECTING, UiState.CONNECTED -> disconnect()
            UiState.IDLE, UiState.ERROR -> beginConnection()
        }
    }

    private fun beginConnection() {
        if (routing.mode == RoutingMode.SELECTED_APPS && routing.selectedPackages.isEmpty()) {
            Toast.makeText(this, R.string.choose_app_before_connect, Toast.LENGTH_LONG).show()
            openAppPicker()
            return
        }

        connectionRequested = true
        activeServerTag = null
        uiState = UiState.DISCOVERING
        val currentSelection = selectedServerProfile()
        connectionMessage = if (
            currentSelection != null && !profileStore.isStale() && serverProfiles.isNotEmpty()
        ) {
            getString(R.string.server_connecting_to, currentSelection.name)
        } else {
            getString(R.string.iran_discovery_loading)
        }
        render()

        discoveryJob?.cancel()
        discoveryJob = lifecycleScope.launch {
            try {
                var profile = profileStore.read()
                val mustRefresh = profile == null || profileStore.isStale() || serverProfiles.isEmpty()
                if (mustRefresh) {
                    val result = withContext(Dispatchers.IO) { discovery.discover() }
                    profileStore.saveDiscovery(result.profile, result.servers)
                    serverProfiles = result.servers
                    reconcileSelectionAfterRefresh()
                    profile = profileStore.read()
                    renderServerSelection()
                }

                if (!connectionRequested) return@launch
                if (profile == null) {
                    failConnection(getString(R.string.iran_profile_missing))
                    return@launch
                }
                val target = selectedServerProfile()
                connectionMessage = if (selectedServerKey == IranVpnProfileStore.AUTO_SELECTION_KEY) {
                    getString(R.string.iran_server_health_checking)
                } else {
                    getString(R.string.server_connecting_to, target?.name ?: profile.name)
                }
                requestVpnPermission(profile)
            } catch (error: Exception) {
                if (!connectionRequested) return@launch
                val cached = profileStore.read()
                if (cached != null && serverProfiles.isNotEmpty()) {
                    connectionMessage = getString(
                        R.string.iran_discovery_fallback,
                        cached.candidateCount,
                        error.localizedMessage ?: getString(R.string.status_error)
                    )
                    requestVpnPermission(cached)
                } else {
                    failConnection(
                        error.localizedMessage?.take(180)
                            ?: getString(R.string.iran_profile_missing)
                    )
                }
            } finally {
                discoveryJob = null
            }
        }
    }

    private fun requestVpnPermission(profileOverride: IranVpnProfile? = null) {
        if (!connectionRequested) return
        pendingProfile = profileOverride ?: profileStore.read()
        if (pendingProfile == null) {
            failConnection(getString(R.string.iran_profile_missing))
            return
        }

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            startTunnel()
        } else {
            uiState = UiState.CONNECTING
            connectionMessage = getString(R.string.vpn_permission_required)
            render()
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun startTunnel() {
        if (!connectionRequested) return
        val profile = pendingProfile ?: profileStore.read()
        if (profile == null) {
            failConnection(getString(R.string.iran_profile_missing))
            return
        }

        val selectedOutboundTag = if (selectedServerKey == IranVpnProfileStore.AUTO_SELECTION_KEY) {
            "auto"
        } else {
            val index = serverProfiles.indexOfFirst { it.selectionKey == selectedServerKey }
            if (index < 0) {
                failConnection(getString(R.string.server_selection_not_found))
                return
            }
            "node-${index + 1}"
        }

        uiState = UiState.CONNECTING
        connectionMessage = if (selectedOutboundTag == "auto") {
            getString(R.string.iran_server_health_checking)
        } else {
            val chosen = selectedServerProfile()
            getString(R.string.server_connecting_to, chosen?.name ?: profile.name)
        }
        render()

        val packages = if (routing.mode == RoutingMode.SELECTED_APPS) {
            routing.selectedPackages
        } else {
            emptySet()
        }
        if (!vpnManager.start(profile, packages, selectedOutboundTag)) {
            failConnection(getString(R.string.status_error))
        }
    }

    private fun disconnect() {
        connectionRequested = false
        activeServerTag = null
        discoveryJob?.cancel()
        vpnManager.stop()
        pendingProfile = null
        uiState = UiState.IDLE
        connectionMessage = getString(R.string.status_disconnected)
        render()
    }

    private fun failConnection(message: String) {
        connectionRequested = false
        activeServerTag = null
        discoveryJob?.cancel()
        uiState = UiState.ERROR
        connectionMessage = message
        render()
    }

    private fun updateCachedProfileSummary() {
        val profile = profileStore.selectedProfile()
        connectionMessage = if (profile != null) {
            getString(R.string.server_connecting_to, profile.name)
        } else {
            getString(R.string.connection_help)
        }
        renderServerSelection()
    }

    private fun render() {
        statusText.text = when (uiState) {
            UiState.IDLE -> getString(R.string.status_ready)
            UiState.DISCOVERING, UiState.CONNECTING -> getString(R.string.status_connecting)
            UiState.CONNECTED -> getString(R.string.status_connected)
            UiState.ERROR -> getString(R.string.status_error)
        }
        detailText.text = when (uiState) {
            UiState.CONNECTED -> {
                val selected = if (selectedServerKey == IranVpnProfileStore.AUTO_SELECTION_KEY) {
                    activeAutomaticServer()
                } else {
                    selectedServerProfile()
                }
                if (selected == null) {
                    getString(R.string.iran_connected_auto_pending, pendingProfile?.candidateCount ?: 0)
                } else {
                    getString(
                        R.string.iran_connected_to_server,
                        selected.name,
                        selected.serverHost,
                        selected.serverPort
                    )
                }
            }
            else -> connectionMessage.ifBlank { getString(R.string.connection_help) }
        }

        val busy = uiState == UiState.DISCOVERING || uiState == UiState.CONNECTING
        findConnectProgress()?.visibility = if (busy) View.VISIBLE else View.GONE
        val isActive = busy || uiState == UiState.CONNECTED
        connectButton.text = if (isActive) {
            getString(R.string.disconnect)
        } else {
            getString(R.string.connect)
        }
        connectButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (isActive) Color.rgb(177, 60, 69) else Color.rgb(29, 139, 103)
        )

        val controlsAvailable = uiState == UiState.IDLE || uiState == UiState.ERROR
        serverSelectButton.isEnabled = controlsAvailable && serverRefreshJob?.isActive != true
        serverRefreshButton.isEnabled = controlsAvailable && serverRefreshJob?.isActive != true
        routeText.text = when (routing.mode) {
            RoutingMode.FULL_TUNNEL -> getString(R.string.full_tunnel_description)
            RoutingMode.SELECTED_APPS -> getString(R.string.selected_apps_description) +
                " " + getString(R.string.choose_apps_count, routing.selectedPackages.size)
        }
    }

    private fun activeAutomaticServer(): IranVpnProfile? {
        val tag = activeServerTag ?: return null
        val index = tag.removePrefix("node-").toIntOrNull()?.minus(1) ?: return null
        return serverProfiles.getOrNull(index) ?: profileStore.readServers().getOrNull(index)
    }

    private fun selectedServerProfile(): IranVpnProfile? =
        if (selectedServerKey == IranVpnProfileStore.AUTO_SELECTION_KEY) {
            profileStore.read()
        } else {
            serverProfiles.firstOrNull { it.selectionKey == selectedServerKey }
        }

    private fun updateServerHealth(tag: String, pingMs: Int, speedMbps: Double, status: String) {
        val index = tag.removePrefix("node-").toIntOrNull()?.minus(1) ?: return
        val current = serverProfiles.getOrNull(index) ?: return
        val updated = current.copy(
            pingMs = pingMs,
            speedMbps = speedMbps,
            healthStatus = status
        )
        serverProfiles = serverProfiles.toMutableList().also { it[index] = updated }
        profileStore.saveServerHealth(updated)
        renderServerSelection()
    }

    private fun serverHealthLabel(server: IranVpnProfile): String = when {
        server.healthStatus == "checking" ->
            getString(R.string.server_health_checking)
        server.healthStatus == "offline" ->
            getString(R.string.server_health_offline)
        server.pingMs > 0 && server.speedMbps > 0.0 ->
            getString(R.string.server_health_metrics, server.pingMs, server.speedMbps)
        server.pingMs > 0 ->
            getString(R.string.server_health_ping_only, server.pingMs)
        else ->
            getString(R.string.server_health_not_tested)
    }

    private fun serverPickerLabel(server: IranVpnProfile): String =
        getString(
            R.string.server_picker_item_with_health,
            server.name,
            server.serverHost,
            server.serverPort,
            serverHealthLabel(server)
        )

    private fun renderServerSelection(message: String? = null) {
        if (!::serverSummaryText.isInitialized) return
        val autoProfile = profileStore.read()
        val selectedManual = selectedServerProfile()
        val selectionText = if (selectedServerKey == IranVpnProfileStore.AUTO_SELECTION_KEY) {
            getString(R.string.server_auto_selected, autoProfile?.candidateCount ?: 0)
        } else if (selectedManual != null) {
            getString(
                R.string.server_manual_selected,
                selectedManual.name,
                selectedManual.serverHost,
                selectedManual.serverPort
            )
        } else {
            getString(R.string.server_selection_not_found)
        }
        val listText = if (serverProfiles.isEmpty()) {
            getString(R.string.server_list_empty)
        } else {
            val checked = serverProfiles.count { it.pingMs > 0 }
            getString(R.string.server_list_count, serverProfiles.size, autoProfile?.sourceCount ?: 0) +
                "\n" + getString(R.string.server_health_count, checked, serverProfiles.size)
        }
        serverSummaryText.text = listOfNotNull(message ?: serverNotice, selectionText, listText).joinToString("\n")
    }

    private fun reconcileSelectionAfterRefresh() {
        if (selectedServerKey != IranVpnProfileStore.AUTO_SELECTION_KEY &&
            serverProfiles.none { it.selectionKey == selectedServerKey }
        ) {
            selectedServerKey = IranVpnProfileStore.AUTO_SELECTION_KEY
            profileStore.saveSelection(selectedServerKey)
        }
    }

    private fun openServerPicker() {
        if (uiState == UiState.CONNECTED || uiState == UiState.CONNECTING ||
            uiState == UiState.DISCOVERING || serverRefreshJob?.isActive == true
        ) {
            Toast.makeText(this, R.string.change_server_disconnect_first, Toast.LENGTH_LONG).show()
            return
        }

        val orderedServers = serverProfiles.sortedWith(
            compareByDescending<IranVpnProfile> { it.pingMs > 0 }
                .thenByDescending { it.speedMbps > 0.0 }
                .thenByDescending { it.speedMbps }
                .thenBy { it.pingMs.takeIf { ping -> ping > 0 } ?: Int.MAX_VALUE }
        )
        val options = buildList {
            add(getString(R.string.server_auto_option))
            orderedServers.forEach { server ->
                add(serverPickerLabel(server))
            }
        }
        val checked = if (selectedServerKey == IranVpnProfileStore.AUTO_SELECTION_KEY) {
            0
        } else {
            orderedServers.indexOfFirst { it.selectionKey == selectedServerKey }
                .takeIf { it >= 0 }?.plus(1) ?: 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.server_selection_title)
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                selectedServerKey = if (which == 0) {
                    IranVpnProfileStore.AUTO_SELECTION_KEY
                } else {
                    orderedServers[which - 1].selectionKey
                }
                profileStore.saveSelection(selectedServerKey)
                serverNotice = null
                renderServerSelection()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.choose_apps_cancel, null)
            .show()
    }

    private fun refreshServers() {
        if (uiState == UiState.CONNECTED || uiState == UiState.CONNECTING ||
            uiState == UiState.DISCOVERING
        ) {
            Toast.makeText(this, R.string.change_server_disconnect_first, Toast.LENGTH_LONG).show()
            return
        }
        if (serverRefreshJob?.isActive == true) return

        serverRefreshButton.isEnabled = false
        serverNotice = getString(R.string.server_refreshing)
        renderServerSelection()
        serverRefreshJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { discovery.discover() }
                profileStore.saveDiscovery(result.profile, result.servers)
                serverProfiles = result.servers
                reconcileSelectionAfterRefresh()
                serverNotice = getString(R.string.server_refresh_success, result.servers.size, result.sourceCount)
                renderServerSelection()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.server_refresh_success_toast, result.servers.size),
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                serverNotice = getString(
                    R.string.server_refresh_failed,
                    error.localizedMessage ?: getString(R.string.status_error)
                )
                renderServerSelection()
            } finally {
                serverRefreshJob = null
                render()
                renderServerSelection()
            }
        }
    }

    private fun findConnectProgress(): ProgressBar? =
        window.decorView.findViewWithTag(CONNECT_PROGRESS_TAG)

    private fun openSettings() {
        if (uiState == UiState.CONNECTED || uiState == UiState.CONNECTING || uiState == UiState.DISCOVERING) {
            Toast.makeText(this, R.string.change_routing_disconnect_first, Toast.LENGTH_LONG).show()
            return
        }

        val options = arrayOf(
            getString(R.string.full_tunnel),
            getString(R.string.selected_apps_mode)
        )
        val checked = if (routing.mode == RoutingMode.FULL_TUNNEL) 0 else 1
        AlertDialog.Builder(this)
            .setTitle(R.string.routing_title)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    routing = RoutingSettings(RoutingMode.FULL_TUNNEL, routing.selectedPackages)
                    routingPreferences.write(routing)
                    render()
                } else {
                    openAppPicker()
                }
            }
            .setNegativeButton(R.string.choose_apps_cancel, null)
            .show()
    }

    private fun openAppPicker() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info: ResolveInfo ->
                val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (appInfo.packageName == packageName) return@mapNotNull null
                LaunchableApp(
                    packageName = appInfo.packageName,
                    label = info.loadLabel(packageManager).toString()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.choose_apps_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val selected = routing.selectedPackages.toMutableSet()
        val checked = apps.map { it.packageName in selected }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_apps_title)
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, index, isChecked ->
                val appPackage = apps[index].packageName
                if (isChecked) selected.add(appPackage) else selected.remove(appPackage)
            }
            .setPositiveButton(R.string.choose_apps_save) { _, _ ->
                if (selected.isEmpty()) {
                    Toast.makeText(this, R.string.choose_app_before_connect, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                routing = RoutingSettings(RoutingMode.SELECTED_APPS, selected.toSet())
                routingPreferences.write(routing)
                render()
            }
            .setNegativeButton(R.string.choose_apps_cancel, null)
            .show()
    }

    private fun checkForUpdates() {
        if (updateJob?.isActive == true) return
        updateButton.isEnabled = false
        updateText.visibility = View.VISIBLE
        updateText.text = getString(R.string.update_checking)
        updateProgress.visibility = View.GONE

        updateJob = lifecycleScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { updater.checkForUpdate() }) {
                    UpdateCheck.Current -> updateText.text = getString(R.string.update_current)
                    is UpdateCheck.Available -> {
                        updateText.text = getString(R.string.update_available, result.release.versionName)
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.update_title)
                            .setMessage(getString(R.string.update_prompt, result.release.versionName))
                            .setPositiveButton(R.string.update_now) { _, _ -> downloadUpdate(result.release) }
                            .setNegativeButton(R.string.update_later, null)
                            .show()
                    }
                }
            } catch (error: Exception) {
                updateText.text = getString(
                    R.string.update_error,
                    error.localizedMessage ?: getString(R.string.status_error)
                )
            } finally {
                updateButton.isEnabled = true
                updateJob = null
            }
        }
    }

    private fun downloadUpdate(release: AppRelease) {
        if (updateJob?.isActive == true) return
        updateButton.isEnabled = false
        updateText.visibility = View.VISIBLE
        updateProgress.visibility = View.VISIBLE
        updateProgress.isIndeterminate = false
        updateProgress.progress = 0

        updateJob = lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { updater.enqueueDownload(release) }
                monitorUpdateDownload(id)
            } catch (error: Exception) {
                updateText.text = getString(
                    R.string.update_download_error,
                    error.localizedMessage ?: getString(R.string.status_error)
                )
                updateProgress.visibility = View.GONE
            } finally {
                updateButton.isEnabled = true
                updateJob = null
            }
        }
    }

    private fun resumePendingUpdateDownload() {
        if (updateJob?.isActive == true) return
        val id = updater.pendingDownloadId()
        if (id < 0L) return

        updateText.visibility = View.VISIBLE
        updateProgress.visibility = View.VISIBLE
        updateProgress.isIndeterminate = false
        updateJob = lifecycleScope.launch {
            try {
                monitorUpdateDownload(id)
            } catch (error: Exception) {
                updateText.text = getString(
                    R.string.update_download_error,
                    error.localizedMessage ?: getString(R.string.status_error)
                )
                updateProgress.visibility = View.GONE
                updater.clearPendingDownload()
            } finally {
                updateButton.isEnabled = true
                updateJob = null
            }
        }
    }

    private suspend fun monitorUpdateDownload(downloadId: Long) {
        while (currentCoroutineContext().isActive) {
            val snapshot = withContext(Dispatchers.IO) { updater.downloadSnapshot(downloadId) }
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val apk = withContext(Dispatchers.IO) {
                        updater.verifiedDownloadedApk(downloadId)
                    }
                    updateProgress.progress = 100
                    updateText.text = getString(R.string.update_downloaded)
                    pendingInstallFile = apk
                    installDownloadedUpdate(apk)
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    throw IOException("Download failed (code \${snapshot.reason}).")
                }
                else -> {
                    updateProgress.progress = snapshot.progressPercent
                    updateText.text = getString(
                        R.string.update_download_progress,
                        snapshot.progressPercent
                    )
                    delay(DOWNLOAD_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun installDownloadedUpdate(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallPackages()) {
            pendingInstallFile = apkFile
            updateText.text = getString(R.string.update_install_permission)
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (error: Exception) {
                updateText.text = getString(
                    R.string.update_install_open_failed,
                    error.localizedMessage ?: getString(R.string.status_error)
                )
            }
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            pendingInstallFile = null
            updater.clearPendingDownload()
        } catch (error: Exception) {
            updateText.text = getString(
                R.string.update_install_open_failed,
                error.localizedMessage ?: getString(R.string.status_error)
            )
        }
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun card(background: Int, stroke: Int): MaterialCardView =
        MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(background)
            strokeWidth = dp(1)
            setStrokeColor(stroke)
        }

    private fun outlinedButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun label(
        text: String,
        size: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, style)
        gravity = Gravity.START
        textDirection = View.TEXT_DIRECTION_LOCALE
        includeFontPadding = true
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private data class LaunchableApp(val packageName: String, val label: String)

    private enum class UiState {
        IDLE, DISCOVERING, CONNECTING, CONNECTED, ERROR
    }

    private companion object {
        const val CONNECT_PROGRESS_TAG = "connection_progress"
        const val DOWNLOAD_POLL_INTERVAL_MS = 700L
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
