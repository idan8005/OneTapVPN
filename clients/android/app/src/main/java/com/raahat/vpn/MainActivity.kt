package com.raahat.vpn

import android.app.Activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var currentMessage by mutableStateOf("برای اتصال رایگان، سرورهای در دسترس را جست‌وجو کنید.")
    private var pendingConfig: String? = null

    private var freeServers by mutableStateOf<List<FreeServer>>(emptyList())
    private var scanning by mutableStateOf(false)
    private var scanMessage by mutableStateOf("")
    private var automaticRetry = false
    private var retryServers: List<FreeServer> = emptyList()
    private var retryIndex = 0
    private var activeFreeServer: FreeServer? = null
    private val healthPreferences by lazy {
        getSharedPreferences("raahat_server_health", Context.MODE_PRIVATE)
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                pendingConfig?.let { RaahatVpnService.start(this, it) }
            } else {
                automaticRetry = false
                retryServers = emptyList()
                activeFreeServer = null
                currentState = ConnectionState.DISCONNECTED
                currentMessage = "مجوز اتصال VPN داده نشد."
            }
            pendingConfig = null
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = runCatching {
                ConnectionState.valueOf(intent.getStringExtra(RaahatVpnService.EXTRA_STATE).orEmpty())
            }.getOrDefault(ConnectionState.DISCONNECTED)
            currentState = state
            currentMessage = intent.getStringExtra(RaahatVpnService.EXTRA_MESSAGE).orEmpty()

            when (state) {
                ConnectionState.CONNECTED -> {
                    activeFreeServer?.let(::markServerHealthy)
                    activeFreeServer = null
                    automaticRetry = false
                    retryServers = emptyList()
                    currentMessage = "اتصال برقرار شد."
                }
                ConnectionState.FAILED -> {
                    activeFreeServer?.let(::markServerFailed)
                    activeFreeServer = null
                    tryNextFreeServer()
                }
                ConnectionState.DISCONNECTED -> {
                    activeFreeServer = null
                    if (automaticRetry) {
                        automaticRetry = false
                        retryServers = emptyList()
                    }
                }
                else -> Unit
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readSavedState()
        setContent {
            MaterialTheme {
                RaahatHome(
                    state = currentState,
                    message = currentMessage,
                    servers = freeServers,
                    scanning = scanning,
                    scanMessage = scanMessage,
                    onConnectFree = ::discoverAndConnectFree,
                    onSelectFree = ::connectSelectedFreeServer,
                    onConnectVless = ::requestVlessConnection,
                    onOpenVpnSettings = ::openVpnSecuritySettings,
                    onDisconnect = {
                        automaticRetry = false
                        retryServers = emptyList()
                        activeFreeServer = null
                        RaahatVpnService.stop(this)
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RaahatVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        readSavedState()
        if (currentState == ConnectionState.FAILED) {
            tryNextFreeServer()
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        backgroundExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun openVpnSecuritySettings() {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS)
        if (intent.resolveActivity(packageManager) != null) {
            runCatching { startActivity(intent) }
                .onFailure { currentMessage = "باز کردن تنظیمات VPN ممکن نشد. از تنظیمات شبکهٔ Android وارد بخش VPN شو." }
        } else {
            currentMessage = "صفحهٔ تنظیمات VPN در این نسخهٔ Android در دسترس نیست؛ از تنظیمات شبکه وارد بخش VPN شو."
        }
    }

    private fun readSavedState() {
        val preferences = getSharedPreferences("raahat_connection", Context.MODE_PRIVATE)
        currentState = runCatching {
            ConnectionState.valueOf(preferences.getString("state", ConnectionState.DISCONNECTED.name).orEmpty())
        }.getOrDefault(ConnectionState.DISCONNECTED)
        currentMessage = preferences.getString("message", "").orEmpty()
    }

    private fun requestVlessConnection(link: String): Boolean {
        return try {
            val profile = VlessRealityProfile.parse(link)
            automaticRetry = false
            retryServers = emptyList()
            activeFreeServer = null
            requestTunnelStart(profile.toSingBoxConfig())
            true
        } catch (error: IllegalArgumentException) {
            currentState = ConnectionState.FAILED
            currentMessage = error.message ?: "لینک سرور معتبر نیست."
            false
        } catch (_: Exception) {
            currentState = ConnectionState.FAILED
            currentMessage = "آماده‌سازی اتصال انجام نشد."
            false
        }
    }

    private fun discoverAndConnectFree() {
        if (scanning || currentState == ConnectionState.CONNECTING ||
            currentState == ConnectionState.CONNECTED
        ) return

        scanning = true
        freeServers = emptyList()
        scanMessage = "در حال دریافت و آزمایش سرورهای عمومی VPN Gate…"
        currentMessage = "در حال جست‌وجوی سرور رایگان پاسخ‌گو…"
        automaticRetry = false
        retryServers = emptyList()
        activeFreeServer = null

        backgroundExecutor.execute {
            val result = runCatching { VPNGateClient().discover() }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                scanning = false
                result.onSuccess { servers ->
                    val availableServers = servers.filterNot { isServerCoolingDown(it.id) }
                    val coolingDownCount = servers.size - availableServers.size
                    freeServers = availableServers
                    scanMessage = "${availableServers.size} سرور آماده؛ TCP پورت‌سنجی شده و UDP هنگام اتصال واقعی آزمایش می‌شود."
                    if (coolingDownCount > 0) {
                        scanMessage += " ${coolingDownCount} سرور که اخیراً اتصال واقعی‌شان ناموفق بوده، موقتاً کنار گذاشته شدند."
                    }
                    retryServers = availableServers.take(MAX_AUTOMATIC_ATTEMPTS)
                    retryIndex = 0
                    automaticRetry = retryServers.isNotEmpty()
                    if (retryServers.isEmpty()) {
                        scanMessage = if (coolingDownCount > 0) {
                            "همه سرورهای پیدا‌شده در ۱۵ دقیقه اخیر ناموفق بوده‌اند؛ برای جلوگیری از تکرار اتصال ناموفق، موقتاً کنار گذاشته شدند. بعداً دوباره جست‌وجو کنید."
                        } else {
                            "سرور قابل استفاده‌ای پیدا نشد. بعداً دوباره تلاش کنید."
                        }
                        currentState = ConnectionState.FAILED
                        currentMessage = scanMessage
                        return@post
                    }
                    activeFreeServer = retryServers.first()
                    currentMessage = "در حال اتصال خودکار به سریع‌ترین سرور…"
                    requestTunnelStart(retryServers.first().profile.toSingBoxConfig())
                }.onFailure { error ->
                    automaticRetry = false
                    retryServers = emptyList()
                    scanMessage = error.message ?: "دریافت سرورهای رایگان ناموفق بود."
                    currentMessage = scanMessage
                    currentState = ConnectionState.FAILED
                }
            }
        }
    }

    private fun connectSelectedFreeServer(server: FreeServer) {
        automaticRetry = false
        retryServers = emptyList()
        activeFreeServer = server
        requestTunnelStart(server.profile.toSingBoxConfig())
    }

    private fun tryNextFreeServer() {
        if (!automaticRetry) return
        val nextIndex = retryIndex + 1
        if (nextIndex >= retryServers.size) {
            automaticRetry = false
            retryServers = emptyList()
            activeFreeServer = null
            currentMessage = "اتصال به سرورهای آزمایش‌شده ناموفق بود. سرورهای ناموفق تا ۱۵ دقیقه از انتخاب خودکار کنار گذاشته شدند؛ بعداً دوباره جست‌وجو کنید."
            return
        }

        retryIndex = nextIndex
        val nextServer = retryServers[nextIndex]
        activeFreeServer = nextServer
        currentState = ConnectionState.CONNECTING
        currentMessage = "سرور قبلی پاسخ اتصال نداد؛ در حال آزمایش سرور بعدی…"
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed && automaticRetry) {
                requestTunnelStart(nextServer.profile.toSingBoxConfig())
            }
        }, RETRY_DELAY_MS)
    }

    private fun isServerCoolingDown(serverId: String): Boolean {
        val key = "failed_at_$serverId"
        val failedAt = healthPreferences.getLong(key, 0L)
        if (failedAt <= 0L) return false

        val coolingDown = ServerHealthPolicy.isCoolingDown(failedAt, System.currentTimeMillis())
        if (!coolingDown) {
            healthPreferences.edit().remove(key).apply()
        }
        return coolingDown
    }

    private fun markServerFailed(server: FreeServer) {
        healthPreferences.edit()
            .putLong("failed_at_${server.id}", System.currentTimeMillis())
            .apply()
        freeServers = freeServers.filterNot { it.id == server.id }
    }

    private fun markServerHealthy(server: FreeServer) {
        healthPreferences.edit().remove("failed_at_${server.id}").apply()
    }

    private fun requestTunnelStart(config: String) {
        pendingConfig = config
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            pendingConfig?.let { RaahatVpnService.start(this, it) }
            pendingConfig = null
        } else {
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    companion object {
        private const val MAX_AUTOMATIC_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 900L
    }
}

@Composable
private fun RaahatHome(
    state: ConnectionState,
    message: String,
    servers: List<FreeServer>,
    scanning: Boolean,
    scanMessage: String,
    onConnectFree: () -> Unit,
    onSelectFree: (FreeServer) -> Unit,
    onConnectVless: (String) -> Boolean,
    onOpenVpnSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var serverLink by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_raahat_vpn),
                contentDescription = "Raahat VPN",
                modifier = Modifier.size(92.dp)
            )
            Text("راحت وی‌پی‌ان", style = MaterialTheme.typography.headlineMedium)
            Text("اتصال امن و ساده", style = MaterialTheme.typography.bodyLarge)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("وضعیت اتصال", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (state) {
                            ConnectionState.CONNECTED -> "متصل"
                            ConnectionState.CONNECTING -> "در حال اتصال"
                            ConnectionState.DISCONNECTING -> "در حال قطع اتصال"
                            ConnectionState.FAILED -> "اتصال ناموفق"
                            ConnectionState.DISCONNECTED -> "قطع است"
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING ||
                        state == ConnectionState.DISCONNECTING
                    ) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onDisconnect,
                            enabled = state != ConnectionState.DISCONNECTING
                        ) { Text("قطع اتصال") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("محافظت در برابر قطع VPN (Kill Switch)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "برای فعال‌سازی، تنظیمات VPN گوشی را باز کن؛ Raahat VPN را انتخاب کن، «Always-on VPN» را روشن کن و سپس «Block connections without VPN» را فعال کن. این دو گزینه را خود Android اعمال می‌کند.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenVpnSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("باز کردن تنظیمات VPN")
                    }
                    Text(
                        "وقتی مسدودسازی روشن باشد، با قطع یا خرابی VPN اینترنت گوشی هم قطع می‌شود. برای قطع دائمی VPN، ابتدا این گزینه‌ها را در تنظیمات Android خاموش کن.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Button(
                onClick = onConnectFree,
                enabled = !scanning && state != ConnectionState.CONNECTING &&
                    state != ConnectionState.CONNECTED && state != ConnectionState.DISCONNECTING,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (scanning) "در حال جست‌وجو…" else "اتصال خودکار رایگان")
            }

            OutlinedButton(
                onClick = { showImportDialog = true },
                enabled = state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING,
                modifier = Modifier.fillMaxWidth()
            ) { Text("افزودن سرور VLESS + REALITY") }

            if (scanMessage.isNotBlank()) {
                Text(scanMessage, style = MaterialTheme.typography.bodyMedium)
            }

            if (servers.isNotEmpty()) {
                Text("سرورهای رایگان پاسخ‌گو", style = MaterialTheme.typography.titleMedium)
                servers.take(8).forEach { server ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(server.name, style = MaterialTheme.typography.titleSmall)
                                val reachability = server.probeLatencyMs?.let { "TCP · ${it} ms" }
                                    ?: "UDP · آزمایش هنگام اتصال"
                                Text(
                                    "${server.countryName} · ${server.transport} · ${reachability}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "${server.activeSessions} اتصال فعال در منبع",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            TextButton(
                                onClick = { onSelectFree(server) },
                                enabled = state != ConnectionState.CONNECTED &&
                                    state != ConnectionState.CONNECTING
                            ) { Text("اتصال") }
                        }
                    }
                }
                Text(
                    "این سرورها عمومی و داوطلبانه هستند؛ اپراتور آن‌ها ممکن است اطلاعات ترافیک را ثبت کند. برای اطلاعات حساس مناسب نیستند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text("نسخه ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("افزودن سرور") },
            text = {
                Column {
                    Text("لینک VLESS + REALITY سرور را وارد کنید.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverLink,
                        onValueChange = {
                            serverLink = it
                            inputError = ""
                        },
                        label = { Text("لینک سرور") },
                        placeholder = { Text("vless://…") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true
                    )
                    if (inputError.isNotBlank()) {
                        Text(inputError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onConnectVless(serverLink)) {
                        showImportDialog = false
                        serverLink = ""
                        inputError = ""
                    } else {
                        inputError = "لینک واردشده معتبر نیست یا پشتیبانی نمی‌شود."
                    }
                }) { Text("ادامه") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("انصراف") }
            }
        )
    }
}
