package com.example.onetapvpn

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: VpnViewModel

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var serverText: TextView
    private lateinit var serverCountText: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var fullTunnelButton: MaterialButton
    private lateinit var selectedAppsModeButton: MaterialButton
    private lateinit var chooseAppsButton: MaterialButton
    private lateinit var refreshServersButton: MaterialButton
    private lateinit var checkUpdateButton: MaterialButton
    private lateinit var downloadUpdateButton: MaterialButton
    private lateinit var updateText: TextView
    private lateinit var updateProgress: ProgressBar
    private lateinit var connectProgress: ProgressBar

    private var latestState = VpnUiState()
    private var availableRelease: AppRelease? = null
    private var pendingInstallFile: File? = null
    private var updateJob: Job? = null
    private val appUpdater by lazy { AppUpdater(applicationContext) }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connect()
        } else {
            viewModel.onVpnPermissionDenied()
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
        OpenVpnStatus.addStateListener(vpnStateListener)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val file = pendingInstallFile
        if (file != null && canInstallPackages()) {
            installDownloadedUpdate(file)
        }
    }

    override fun onDestroy() {
        OpenVpnStatus.removeStateListener(vpnStateListener)
        updateJob?.cancel()
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
        statusText = label(getString(R.string.status_scanning), 24f, Color.WHITE, Typeface.BOLD).apply {
            id = R.id.connection_status
            gravity = Gravity.CENTER
        }
        connectionContent.addView(statusText, matchWrap().apply { topMargin = dp(8) })

        connectProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
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
        connectionCard.addView(connectionContent)
        content.addView(connectionCard, matchWrap().apply { topMargin = dp(24) })

        val serverCard = card(Color.rgb(14, 25, 44), Color.rgb(34, 51, 75))
        val serverContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        serverContent.addView(label(getString(R.string.server_card_title), 14f, Color.WHITE, Typeface.BOLD))
        serverText = label(getString(R.string.server_summary_empty), 14f, Color.rgb(174, 192, 218))
        serverContent.addView(serverText, matchWrap().apply { topMargin = dp(8) })
        serverCountText = label("", 12f, Color.rgb(133, 153, 182))
        serverContent.addView(serverCountText, matchWrap().apply { topMargin = dp(6) })
        refreshServersButton = outlinedButton(getString(R.string.refresh_servers)) {
            viewModel.refreshServers()
        }.apply { id = R.id.refresh_servers_button }
        serverContent.addView(refreshServersButton, matchWrap().apply { topMargin = dp(12) })
        serverCard.addView(serverContent)
        content.addView(serverCard, matchWrap().apply { topMargin = dp(14) })

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
        downloadUpdateButton = MaterialButton(this).apply {
            id = R.id.download_update_button
            text = getString(R.string.download_update)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { downloadAvailableUpdate() }
        }
        updateContent.addView(downloadUpdateButton, matchWrap().apply { topMargin = dp(8) })
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

    private fun onConnectionButtonPressed() {
        when (latestState.status) {
            VpnStatus.CONNECTED -> viewModel.disconnect()
            VpnStatus.CONNECTING -> Unit
            else -> {
                val permissionIntent = VpnService.prepare(this)
                if (permissionIntent == null) {
                    viewModel.connect()
                } else {
                    vpnPermissionLauncher.launch(permissionIntent)
                }
            }
        }
    }

    private fun render(state: VpnUiState) {
        latestState = state

        statusText.text = when (state.status) {
            VpnStatus.SCANNING -> getString(R.string.status_scanning)
            VpnStatus.READY -> getString(R.string.status_ready)
            VpnStatus.CONNECTING -> getString(R.string.status_connecting)
            VpnStatus.CONNECTED -> getString(R.string.status_connected)
            VpnStatus.DISCONNECTED -> getString(R.string.status_disconnected)
            VpnStatus.ERROR -> getString(R.string.status_error)
        }
        detailText.text = state.message
        connectProgress.visibility = if (
            state.status == VpnStatus.SCANNING ||
            state.status == VpnStatus.CONNECTING ||
            state.isRefreshing
        ) View.VISIBLE else View.GONE

        connectButton.isEnabled = state.status != VpnStatus.CONNECTING && !state.isBusy
        connectButton.text = if (state.status == VpnStatus.CONNECTED) {
            getString(R.string.disconnect)
        } else {
            getString(R.string.connect)
        }
        connectButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (state.status == VpnStatus.CONNECTED) Color.rgb(190, 62, 70)
            else Color.rgb(31, 144, 109)
        )

        val server = state.currentServer
        serverText.text = if (server == null) {
            getString(R.string.server_summary_empty)
        } else {
            getString(
                R.string.server_summary_format,
                server.country,
                server.pingMs,
                server.speedMbps
            )
        }
        serverCountText.text = if (state.servers.isEmpty()) "" else {
            getString(R.string.server_count, state.servers.size)
        }
        refreshServersButton.isEnabled = !state.isRefreshing &&
            state.status != VpnStatus.CONNECTED &&
            state.status != VpnStatus.CONNECTING

        val routingCanChange = state.status != VpnStatus.CONNECTED &&
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
        availableRelease = null
        downloadUpdateButton.visibility = View.GONE
        checkUpdateButton.isEnabled = false
        updateText.text = getString(R.string.update_checking)

        updateJob = lifecycleScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { appUpdater.checkForUpdate() }) {
                    UpdateCheck.Current -> updateText.text = getString(R.string.update_current)
                    is UpdateCheck.Available -> {
                        availableRelease = result.release
                        updateText.text = getString(R.string.update_available, result.release.versionName)
                        downloadUpdateButton.visibility = View.VISIBLE
                    }
                }
            } catch (error: Exception) {
                updateText.text = getString(
                    R.string.update_error,
                    error.localizedMessage ?: getString(R.string.status_error)
                )
            } finally {
                checkUpdateButton.isEnabled = true
            }
        }
    }

    private fun downloadAvailableUpdate() {
        val release = availableRelease ?: return
        if (updateJob?.isActive == true) return
        downloadUpdateButton.isEnabled = false
        updateProgress.visibility = View.VISIBLE
        updateProgress.isIndeterminate = false
        updateProgress.progress = 0

        updateJob = lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { appUpdater.enqueueDownload(release) }
                while (isActive) {
                    val snapshot = withContext(Dispatchers.IO) {
                        appUpdater.downloadSnapshot(id)
                    }
                    when (snapshot.status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val apk = withContext(Dispatchers.IO) {
                                appUpdater.verifiedDownloadedApk(id)
                            }
                            appUpdater.clearPendingDownload()
                            updateProgress.progress = 100
                            updateText.text = getString(R.string.update_downloaded)
                            pendingInstallFile = apk
                            installDownloadedUpdate(apk)
                            break
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
            } catch (error: Exception) {
                updateText.text = getString(
                    R.string.update_download_error,
                    error.localizedMessage ?: getString(R.string.status_error)
                )
                updateProgress.visibility = View.GONE
                downloadUpdateButton.isEnabled = true
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
            pendingInstallFile = null
            startActivity(installIntent)
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
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
