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
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus as OpenVpnStatus
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: VpnViewModel

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var fullTunnelButton: MaterialButton
    private lateinit var selectedAppsModeButton: MaterialButton
    private lateinit var chooseAppsButton: MaterialButton
    private lateinit var checkUpdateButton: MaterialButton
    private lateinit var updateText: TextView
    private lateinit var updateProgress: ProgressBar
    private lateinit var connectProgress: ProgressBar
    private lateinit var iranProfileStatusText: TextView
    private lateinit var refreshIranServersButton: MaterialButton

    private var latestState = VpnUiState()
    private var pendingInstallFile: File? = null
    private var updateJob: Job? = null
    private val appUpdater by lazy { AppUpdater(applicationContext) }
    private val iranVpnManager by lazy { IranVpnManager(applicationContext) }
    private val iranProfileStore by lazy { IranVpnProfileStore(applicationContext) }
    private val iranDiscovery by lazy { IranServerDiscovery() }
    private var iranDiscoveryJob: Job? = null
    private var iranConnectAfterDiscovery = false
    private var iranReceiverRegistered = false
    private var iranModeActive = false
    private var iranConnectionPending = false
    private var iranTunnelVerified = false
    private var iranFallbackStarted = false
    private var iranProbeJob: Job? = null

    private val iranVpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startIranTunnel()
        } else {
            iranProfileStatusText.text = getString(R.string.vpn_permission_denied)
        }
    }

    private val iranStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_IRAN_STATUS) return
            val message = intent.getStringExtra(EXTRA_IRAN_STATUS)
                ?: getString(R.string.status_error)
            iranProfileStatusText.text = message

            val started = message == getString(R.string.iran_engine_started)
            val disconnected = message == getString(R.string.iran_disconnected)
            val errorPrefix = getString(R.string.iran_engine_error, "")
            val failed = message.startsWith(errorPrefix)

            if (started) {
                iranModeActive = true
                if (iranConnectionPending) startIranConnectivityCheck()
            } else if (disconnected) {
                val unexpectedlyStopped = iranTunnelVerified && !iranFallbackStarted
                iranModeActive = false
                iranTunnelVerified = false
                if (unexpectedlyStopped) {
                    iranConnectionPending = true
                    fallbackToVpnGate(getString(R.string.iran_tunnel_lost))
                }
            } else if (failed) {
                iranModeActive = false
                if (iranConnectionPending) fallbackToVpnGate(message)
            }
            render(latestState)
        }
    }

    private val vpnStateListener = object : OpenVpnStatus.StateListener {
        override fun updateState(
            state: String?,
            logmessage: String?,
            localizedResId: Int,
            level: ConnectionStatus?,
            intent: Intent?
        ) {
            runOnUiThread {
                viewModel.onVpnState(state, logmessage, level)
            }
        }

        override fun setConnectedVPN(uuid: String?) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 16, 31)
        window.navigationBarColor = Color.rgb(9, 16, 31)
        window.decorView.systemUiVisibility = 0

        viewModel = ViewModelProvider(this)[VpnViewModel::class.java]
        setContentView(createContent())
        registerIranStatusReceiver()
        renderSavedIranProfile()
        OpenVpnStatus.addStateListener(vpnStateListener)
        discoverIranServers()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
        lifecycleScope.launch {
            delay(1_500L)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                checkForUpdates()
            }
        }
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
        OpenVpnStatus.removeStateListener(vpnStateListener)
        if (iranReceiverRegistered) unregisterReceiver(iranStatusReceiver)
        iranDiscoveryJob?.cancel()
        updateJob?.cancel()
        iranProbeJob?.cancel()
        super.onDestroy()
    }

    private fun createContent(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(9, 16, 31))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_raahat_vpn)
            contentDescription = getString(R.string.app_name)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))

        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(
            label(getString(R.string.app_name), 23f, Color.WHITE, Typeface.BOLD).apply {
                id = R.id.brand_title
            }
        )
        brand.addView(label(getString(R.string.app_tagline), 13f, Color.rgb(156, 174, 201)))
        brand.addView(
            label(getString(R.string.app_version, BuildConfig.VERSION_NAME), 11f, Color.rgb(126, 151, 183))
        )
        header.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        content.addView(header, matchWrap())

        val connectionCard = card(Color.rgb(18, 31, 53), Color.rgb(42, 61, 89))
        val connectionContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(24), dp(18), dp(22))
        }
        connectionContent.addView(
            label(getString(R.string.connection_title), 13f, Color.rgb(199, 215, 238), Typeface.BOLD)
        )
        statusText = label(getString(R.string.status_ready), 24f, Color.WHITE, Typeface.BOLD).apply {
            id = R.id.connection_status
            gravity = Gravity.CENTER
        }
        connectionContent.addView(statusText, matchWrap().apply { topMargin = dp(8) })

        connectProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        connectionContent.addView(connectProgress, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            topMargin = dp(10)
        })

        connectButton = MaterialButton(this).apply {
            id = R.id.connect_button
            text = getString(R.string.connect)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            cornerRadius = dp(100)
            insetTop = dp(4)
            insetBottom = dp(4)
            setOnClickListener { onConnectionButtonPressed() }
        }
        connectionContent.addView(connectButton, LinearLayout.LayoutParams(dp(174), dp(174)).apply {
            topMargin = dp(16)
            bottomMargin = dp(12)
        })

        detailText = label(getString(R.string.connection_help), 13f, Color.rgb(164, 184, 213)).apply {
            gravity = Gravity.CENTER
        }
        connectionContent.addView(detailText, matchWrap())

        iranProfileStatusText = label(getString(R.string.iran_profile_empty), 12f, Color.rgb(174, 192, 218)).apply {
            gravity = Gravity.CENTER
        }
        connectionContent.addView(iranProfileStatusText, matchWrap().apply { topMargin = dp(10) })
        refreshIranServersButton = outlinedButton(getString(R.string.iran_refresh_servers)) {
            discoverIranServers(forceRefresh = true)
        }
        connectionContent.addView(refreshIranServersButton, matchWrap().apply { topMargin = dp(8) })

        connectionCard.addView(connectionContent)
        content.addView(connectionCard, matchWrap().apply { topMargin = dp(24) })

        val routingCard = card(Color.rgb(14, 25, 44), Color.rgb(34, 51, 75))
        val routingContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        routingContent.addView(label(getString(R.string.routing_title), 15f, Color.WHITE, Typeface.BOLD))
        routingContent.addView(
            label(getString(R.string.routing_help), 12f, Color.rgb(158, 178, 205)),
            matchWrap().apply { topMargin = dp(6) }
        )
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fullTunnelButton = MaterialButton(this).apply {
            id = R.id.full_tunnel_button
            text = getString(R.string.full_tunnel)
            isAllCaps = false
            isCheckable = true
            setOnClickListener {
                viewModel.setRouting(
                    RoutingSettings(
                        mode = RoutingMode.FULL_TUNNEL,
                        selectedPackages = latestState.routing.selectedPackages
                    )
                )
            }
        }
        selectedAppsModeButton = MaterialButton(this).apply {
            id = R.id.selected_apps_mode_button
            text = getString(R.string.selected_apps_mode)
            isAllCaps = false
            isCheckable = true
            setOnClickListener {
                viewModel.setRouting(
                    RoutingSettings(
                        mode = RoutingMode.SELECTED_APPS,
                        selectedPackages = latestState.routing.selectedPackages
                    )
                )
            }
        }
        modeRow.addView(fullTunnelButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginEnd = dp(5)
        })
        modeRow.addView(selectedAppsModeButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(5)
        })
        routingContent.addView(modeRow, matchWrap().apply { topMargin = dp(12) })

        chooseAppsButton = outlinedButton(getString(R.string.app_selection_button, 0)) {
            openAppPicker()
        }.apply { id = R.id.choose_apps_button }
        routingContent.addView(chooseAppsButton, matchWrap().apply { topMargin = dp(8) })
        routingCard.addView(routingContent)
        content.addView(routingCard, matchWrap().apply { topMargin = dp(14) })

        val updateCard = card(Color.rgb(14, 25, 44), Color.rgb(34, 51, 75))
        val updateContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        updateContent.addView(label(getString(R.string.update_title), 15f, Color.WHITE, Typeface.BOLD))
        updateText = label(getString(R.string.update_initial), 13f, Color.rgb(174, 192, 218))
        updateContent.addView(updateText, matchWrap().apply { topMargin = dp(6) })
        checkUpdateButton = outlinedButton(getString(R.string.check_updates)) {
            checkForUpdates()
        }.apply { id = R.id.check_updates_button }
        updateContent.addView(checkUpdateButton, matchWrap().apply { topMargin = dp(12) })
        updateProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        updateContent.addView(updateProgress, matchWrap().apply { topMargin = dp(8) })
        updateCard.addView(updateContent)
        content.addView(updateCard, matchWrap().apply { topMargin = dp(14) })

        val noticeCard = card(Color.rgb(39, 32, 23), Color.rgb(95, 72, 43))
        val noticeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        noticeContent.addView(label(getString(R.string.security_notice_title), 13f, Color.rgb(245, 206, 153), Typeface.BOLD))
        noticeContent.addView(
            label(getString(R.string.security_notice), 12f, Color.rgb(220, 198, 170)),
            matchWrap().apply { topMargin = dp(6) }
        )
        noticeCard.addView(noticeContent)
        content.addView(noticeCard, matchWrap().apply { topMargin = dp(14) })

        return scroll
    }

    private fun registerIranStatusReceiver() {
        val filter = IntentFilter(ACTION_IRAN_STATUS)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            iranStatusReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        iranReceiverRegistered = true
    }

    private fun renderSavedIranProfile() {
        val profile = iranProfileStore.read()
        iranProfileStatusText.text = if (profile == null) {
            getString(R.string.iran_profile_empty)
        } else {
            getString(
                R.string.iran_profile_loaded,
                profile.candidateCount,
                profile.sourceCount
            )
        }
    }

    private fun discoverIranServers(forceRefresh: Boolean = false, connectAfter: Boolean = false) {
        if (connectAfter) {
            iranConnectionPending = true
            iranTunnelVerified = false
            iranProfileStatusText.text = getString(R.string.iran_discovery_loading)
            render(latestState)
        }

        if (iranDiscoveryJob?.isActive == true) {
            iranConnectAfterDiscovery = iranConnectAfterDiscovery || connectAfter
            return
        }

        val cached = iranProfileStore.read()
        if (!forceRefresh && cached != null && !iranProfileStore.isStale()) {
            renderSavedIranProfile()
            if (connectAfter) requestIranVpnPermission()
            return
        }

        iranConnectAfterDiscovery = connectAfter
        iranProfileStatusText.text = getString(R.string.iran_discovery_loading)
        refreshIranServersButton.isEnabled = false

        iranDiscoveryJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { iranDiscovery.discover() }
                iranProfileStore.save(result.profile)
                iranProfileStatusText.text = getString(
                    R.string.iran_discovered_count,
                    result.profile.candidateCount,
                    result.sourceCount
                )
                val shouldConnect = iranConnectAfterDiscovery
                iranConnectAfterDiscovery = false
                if (shouldConnect) requestIranVpnPermission()
            } catch (error: Exception) {
                val cachedProfile = iranProfileStore.read()
                if (cachedProfile != null) {
                    iranProfileStatusText.text = getString(
                        R.string.iran_discovery_fallback,
                        cachedProfile.candidateCount,
                        error.localizedMessage ?: getString(R.string.status_error)
                    )
                    if (iranConnectAfterDiscovery) {
                        iranConnectAfterDiscovery = false
                        requestIranVpnPermission()
                    }
                } else {
                    iranProfileStatusText.text = getString(
                        R.string.iran_discovery_failed,
                        error.localizedMessage ?: getString(R.string.status_error)
                    )
                    if (iranConnectAfterDiscovery) {
                        iranConnectAfterDiscovery = false
                        fallbackToVpnGate(getString(R.string.iran_fallback_message))
                    }
                }
            } finally {
                refreshIranServersButton.isEnabled = !iranConnectionPending && !iranTunnelVerified
                iranDiscoveryJob = null
            }
        }
    }

    private fun requestIranConnection() {
        if (iranConnectionPending || iranTunnelVerified) return
        iranFallbackStarted = false
        iranConnectionPending = true
        iranTunnelVerified = false
        val cached = iranProfileStore.read()
        if (cached == null || iranProfileStore.isStale()) {
            discoverIranServers(forceRefresh = true, connectAfter = true)
        } else {
            renderSavedIranProfile()
            requestIranVpnPermission()
        }
        render(latestState)
    }

    private fun requestIranVpnPermission() {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            startIranTunnel()
        } else {
            iranVpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun startIranTunnel() {
        if (latestState.routing.mode == RoutingMode.SELECTED_APPS &&
            latestState.routing.selectedPackages.isEmpty()
        ) {
            iranConnectionPending = false
            iranProfileStatusText.text = getString(R.string.choose_app_before_connect)
            Toast.makeText(this, R.string.choose_app_before_connect, Toast.LENGTH_LONG).show()
            render(latestState)
            return
        }

        val profile = iranProfileStore.read()
        if (profile == null) {
            fallbackToVpnGate(getString(R.string.iran_profile_missing))
            return
        }

        iranProbeJob?.cancel()
        iranConnectionPending = true
        iranTunnelVerified = false
        iranProfileStatusText.text = getString(R.string.iran_engine_starting)
        render(latestState)

        lifecycleScope.launch {
            if (latestState.status == VpnStatus.CONNECTED ||
                latestState.status == VpnStatus.CONNECTING
            ) {
                viewModel.disconnect()
                delay(700L)
            }

            val selectedPackages = if (latestState.routing.mode == RoutingMode.SELECTED_APPS) {
                latestState.routing.selectedPackages
            } else {
                emptySet()
            }
            val started = iranVpnManager.start(profile, selectedPackages)
            if (!started) {
                fallbackToVpnGate(getString(R.string.iran_fallback_message))
            } else {
                iranModeActive = true
                iranProfileStatusText.text = getString(R.string.iran_engine_starting)
                render(latestState)
                startIranConnectivityCheck()
            }
        }
    }

    private fun startIranConnectivityCheck() {
        if (iranProbeJob?.isActive == true) return
        iranProbeJob = lifecycleScope.launch {
            var reachable = false
            for (attempt in 0 until CONNECTIVITY_PROBE_ATTEMPTS) {
                if (!iranConnectionPending || !iranModeActive) return@launch
                if (attempt > 0) delay(CONNECTIVITY_PROBE_INTERVAL_MS)
                reachable = withContext(Dispatchers.IO) { probeTunnelConnectivity() }
                if (reachable) break
            }

            if (!iranConnectionPending || !iranModeActive) return@launch
            if (reachable) {
                iranConnectionPending = false
                iranTunnelVerified = true
                iranFallbackStarted = false
                iranProfileStatusText.text = getString(R.string.iran_connected)
                refreshIranServersButton.isEnabled = true
                render(latestState)
            } else {
                fallbackToVpnGate(getString(R.string.iran_fallback_message))
            }
        }
    }

    private fun probeTunnelConnectivity(): Boolean {
        val connection = try {
            (URL(CONNECTIVITY_TEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECTIVITY_PROBE_TIMEOUT_MS
                readTimeout = CONNECTIVITY_PROBE_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "RaahatVPN-ConnectivityCheck")
            }
        } catch (_: Exception) {
            return false
        }
        return try {
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun fallbackToVpnGate(reason: String) {
        if (iranFallbackStarted || !iranConnectionPending) return
        iranFallbackStarted = true
        iranProbeJob?.cancel()
        iranVpnManager.stop()
        iranModeActive = false
        iranTunnelVerified = false
        iranConnectionPending = false
        iranProfileStatusText.text = getString(R.string.iran_fallback_to_classic, reason)
        refreshIranServersButton.isEnabled = false
        render(latestState)

        lifecycleScope.launch {
            delay(FALLBACK_SWITCH_DELAY_MS)
            viewModel.connect()
        }
    }

    private fun onConnectionButtonPressed() {
        if (iranModeActive || iranConnectionPending || iranTunnelVerified) {
            iranProbeJob?.cancel()
            iranVpnManager.stop()
            iranModeActive = false
            iranConnectionPending = false
            iranTunnelVerified = false
            iranFallbackStarted = false
            iranProfileStatusText.text = getString(R.string.iran_disconnected)
            refreshIranServersButton.isEnabled = true
            render(latestState)
            return
        }

        when (latestState.status) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING, VpnStatus.SCANNING -> {
                viewModel.disconnect()
                iranFallbackStarted = false
                iranProfileStatusText.text = getString(R.string.iran_disconnected)
            }
            else -> requestIranConnection()
        }
    }

    private fun render(state: VpnUiState) {
        latestState = state
        val isConnected = iranTunnelVerified || state.status == VpnStatus.CONNECTED
        val isStarting = iranConnectionPending || iranModeActive ||
            state.status == VpnStatus.CONNECTING ||
            (state.status == VpnStatus.SCANNING && state.isBusy)

        statusText.text = when {
            iranTunnelVerified -> getString(R.string.status_connected)
            iranConnectionPending || iranModeActive -> getString(R.string.status_connecting)
            else -> when (state.status) {
                VpnStatus.SCANNING -> getString(R.string.status_scanning)
                VpnStatus.READY -> getString(R.string.status_ready)
                VpnStatus.CONNECTING -> getString(R.string.status_connecting)
                VpnStatus.CONNECTED -> getString(R.string.status_connected)
                VpnStatus.DISCONNECTED -> getString(R.string.status_disconnected)
                VpnStatus.ERROR -> getString(R.string.status_error)
            }
        }

        detailText.text = when {
            iranConnectionPending || iranModeActive || iranTunnelVerified -> iranProfileStatusText.text
            else -> state.message
        }
        connectProgress.visibility = if (
            (isStarting && !isConnected) ||
            state.status == VpnStatus.SCANNING ||
            state.isRefreshing
        ) View.VISIBLE else View.GONE

        connectButton.isEnabled = isStarting || (
            state.status != VpnStatus.CONNECTING && !state.isBusy
        )
        connectButton.text = if (isConnected || isStarting) {
            getString(R.string.disconnect)
        } else {
            getString(R.string.connect)
        }
        connectButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (isConnected || isStarting) Color.rgb(190, 62, 70)
            else Color.rgb(31, 144, 109)
        )

        val routingCanChange = !isConnected && !isStarting &&
            state.status != VpnStatus.CONNECTED &&
            state.status != VpnStatus.CONNECTING
        fullTunnelButton.isEnabled = routingCanChange
        selectedAppsModeButton.isEnabled = routingCanChange
        chooseAppsButton.isEnabled = routingCanChange
        fullTunnelButton.isChecked = state.routing.mode == RoutingMode.FULL_TUNNEL
        selectedAppsModeButton.isChecked = state.routing.mode == RoutingMode.SELECTED_APPS
        chooseAppsButton.visibility = if (state.routing.mode == RoutingMode.SELECTED_APPS) {
            View.VISIBLE
        } else {
            View.GONE
        }
        chooseAppsButton.text = getString(
            R.string.app_selection_button,
            state.routing.selectedPackages.size
        )
        refreshIranServersButton.isEnabled = !isConnected && !isStarting &&
            iranDiscoveryJob?.isActive != true
    }

    private fun openAppPicker() {
        val apps = getLaunchableApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.choose_apps_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val selected = latestState.routing.selectedPackages.toMutableSet()
        val checked = apps.map { it.packageName in selected }.toBooleanArray()
        val labels = apps.map { it.label }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_apps_title)
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                val packageName = apps[index].packageName
                if (isChecked) selected.add(packageName) else selected.remove(packageName)
            }
            .setPositiveButton(R.string.choose_apps_save) { _, _ ->
                viewModel.setRouting(
                    RoutingSettings(
                        mode = RoutingMode.SELECTED_APPS,
                        selectedPackages = selected.toSet()
                    )
                )
            }
            .setNegativeButton(R.string.choose_apps_cancel, null)
            .show()
    }

    private fun getLaunchableApps(): List<LaunchableApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo: ResolveInfo ->
                val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                val appPackage = appInfo.packageName
                if (appPackage == packageName) return@mapNotNull null
                LaunchableApp(
                    packageName = appPackage,
                    label = resolveInfo.loadLabel(packageManager).toString()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun checkForUpdates() {
        if (updateJob?.isActive == true) return
        checkUpdateButton.isEnabled = false
        updateProgress.visibility = View.GONE
        updateText.text = getString(R.string.update_checking)

        updateJob = lifecycleScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { appUpdater.checkForUpdate() }) {
                    UpdateCheck.Current -> {
                        updateText.text = getString(R.string.update_current)
                        checkUpdateButton.isEnabled = true
                    }
                    is UpdateCheck.Available -> {
                        updateText.text = getString(R.string.update_available, result.release.versionName)
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.update_title)
                            .setMessage(getString(R.string.update_prompt, result.release.versionName))
                            .setPositiveButton(R.string.update_now) { _, _ ->
                                downloadAvailableUpdate(result.release)
                            }
                            .setNegativeButton(R.string.update_later, null)
                            .show()
                        checkUpdateButton.isEnabled = true
                    }
                }
            } catch (error: Exception) {
                updateText.text = getString(
                    R.string.update_error,
                    error.localizedMessage ?: getString(R.string.status_error)
                )
                checkUpdateButton.isEnabled = true
            } finally {
                if (updateJob?.isActive != true) checkUpdateButton.isEnabled = true
            }
        }
    }

    private fun downloadAvailableUpdate(release: AppRelease) {
        if (updateJob?.isActive == true) {
            lifecycleScope.launch {
                updateJob?.join()
                downloadAvailableUpdate(release)
            }
            return
        }

        updateProgress.visibility = View.VISIBLE
        updateProgress.isIndeterminate = false
        updateProgress.progress = 0
        checkUpdateButton.isEnabled = false

        updateJob = lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { appUpdater.enqueueDownload(release) }
                monitorUpdateDownload(id)
            } catch (error: Exception) {
                updateText.text = getString(
                    R.string.update_download_error,
                    error.localizedMessage ?: getString(R.string.status_error)
                )
                updateProgress.visibility = View.GONE
            } finally {
                checkUpdateButton.isEnabled = true
            }
        }
    }

    private fun resumePendingUpdateDownload() {
        if (updateJob?.isActive == true) return
        val id = appUpdater.pendingDownloadId()
        if (id < 0L) return

        updateJob = lifecycleScope.launch {
            updateProgress.visibility = View.VISIBLE
            updateProgress.isIndeterminate = false
            try {
                monitorUpdateDownload(id)
            } catch (error: Exception) {
                updateText.text = getString(
                    R.string.update_download_error,
                    error.localizedMessage ?: getString(R.string.status_error)
                )
                updateProgress.visibility = View.GONE
                appUpdater.clearPendingDownload()
            } finally {
                checkUpdateButton.isEnabled = true
            }
        }
    }

    private suspend fun monitorUpdateDownload(downloadId: Long) {
        while (currentCoroutineContext().isActive) {
            val snapshot = withContext(Dispatchers.IO) {
                appUpdater.downloadSnapshot(downloadId)
            }
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val apk = withContext(Dispatchers.IO) {
                        appUpdater.verifiedDownloadedApk(downloadId)
                    }
                    updateProgress.progress = 100
                    updateText.text = getString(R.string.update_downloaded)
                    pendingInstallFile = apk
                    installDownloadedUpdate(apk)
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    throw IOException("Download failed (code ${snapshot.reason}).")
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
            val contentUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
            pendingInstallFile = null
            appUpdater.clearPendingDownload()
        } catch (error: Exception) {
            updateText.text = getString(
                R.string.update_install_open_failed,
                error.localizedMessage ?: getString(R.string.status_error)
            )
        }
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun card(background: Int, stroke: Int): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(22).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(background)
        strokeWidth = dp(1)
        setStrokeColor(stroke)
    }

    private fun outlinedButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
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

    private data class LaunchableApp(
        val packageName: String,
        val label: String
    )

    private companion object {
        const val DOWNLOAD_POLL_INTERVAL_MS = 650L
        const val CONNECTIVITY_TEST_URL = "https://cp.cloudflare.com/generate_204"
        const val CONNECTIVITY_PROBE_TIMEOUT_MS = 5_000
        const val CONNECTIVITY_PROBE_ATTEMPTS = 5
        const val CONNECTIVITY_PROBE_INTERVAL_MS = 2_500L
        const val FALLBACK_SWITCH_DELAY_MS = 800L
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val ACTION_IRAN_STATUS = "com.example.onetapvpn.iran.STATUS"
        const val EXTRA_IRAN_STATUS = "status"
    }
}
