package com.example.onetapvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Optional sing-box engine for user-supplied VLESS + REALITY profiles.
 * It runs as a dedicated Android VpnService and does not replace the OpenVPN engine.
 */
internal class IranVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var commandServer: CommandServer? = null
    private var activeServerMonitorJob: kotlinx.coroutines.Job? = null
    private var startupJob: kotlinx.coroutines.Job? = null
    private var selectedOutboundTag: String = AUTO_OUTBOUND_TAG
    private var tunFileDescriptor: ParcelFileDescriptor? = null
    private var currentConfig: String? = null
    private var selectedPackages: List<String> = emptyList()
    private var defaultInterfaceListener: InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.iran_engine_starting)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            publishStatus(IranVpnContract.STATE_DISCONNECTED, getString(R.string.iran_disconnected))
            stopEngine()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val config = intent?.getStringExtra(EXTRA_CONFIG)
            ?: currentConfig
            ?: run {
                publishStatus(IranVpnContract.STATE_ERROR, getString(R.string.iran_profile_missing))
                stopSelf(startId)
                return START_NOT_STICKY
            }
        val scanOnly = intent?.getBooleanExtra(EXTRA_SCAN_ONLY, false) == true
        val runtimeConfig = if (scanOnly) createHealthScanConfig(config) else config
        selectedPackages = if (scanOnly) emptyList() else intent?.getStringArrayListExtra(EXTRA_PACKAGES)
            ?.distinct()
            ?.filter { it.isNotBlank() }
            .orEmpty()
        selectedOutboundTag = intent?.getStringExtra(EXTRA_SELECTED_OUTBOUND)
            ?.takeIf { it.isNotBlank() }
            ?: AUTO_OUTBOUND_TAG
        currentConfig = runtimeConfig

        startupJob?.cancel()
        startupJob = serviceScope.launch {
            try {
                ensureLibboxSetup()
                val server = commandServer ?: CommandServer(this@IranVpnService, this@IranVpnService).also {
                    it.start()
                    commandServer = it
                }
                server.startOrReloadService(runtimeConfig, createOverrideOptions())

                val secret = readControllerSecret(runtimeConfig)
                    ?: error("The local server-health controller is not configured.")
                awaitControllerReady(secret)
                updateForegroundNotification(getString(R.string.iran_server_health_checking))
                val healthResults = checkAllServers(secret, runtimeConfig, runSpeedTest = !scanOnly)
                val available = healthResults.filter { it.pingMs > 0 }
                if (scanOnly) {
                    publishScanComplete(available.size, healthResults.size)
                    startupJob = null
                    stopEngine()
                    stopSelf(startId)
                    return@launch
                }
                if (available.isEmpty()) {
                    error(getString(R.string.iran_no_server_passed_test))
                }

                val chosenTag = if (selectedOutboundTag == AUTO_OUTBOUND_TAG) {
                    selectBestServer(healthResults)?.tag
                        ?: error(getString(R.string.iran_no_server_passed_test))
                } else {
                    val manuallyChosen = healthResults.firstOrNull { it.tag == selectedOutboundTag }
                    if (manuallyChosen == null || manuallyChosen.pingMs <= 0) {
                        error(getString(R.string.iran_manual_server_unavailable))
                    }
                    selectedOutboundTag
                }

                selectOutbound(secret, chosenTag)
                kotlinx.coroutines.delay(350)
                if (!verifyInternetThroughLocalProxy()) {
                    error(getString(R.string.iran_internet_test_failed))
                }
                if (!verifyInternetThroughTun()) {
                    error(getString(R.string.iran_tun_internet_test_failed))
                }

                val chosen = healthResults.first { it.tag == chosenTag }
                publishStatus(
                    IranVpnContract.STATE_CONNECTED,
                    getString(R.string.iran_connected_verified),
                    chosenTag
                )
                updateForegroundNotification(
                    getString(R.string.iran_connected_to_server, chosen.name, chosen.host, chosen.port)
                )
                startActiveServerMonitor(secret, healthResults, chosenTag)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "VPN startup or Internet verification failed", error)
                if (scanOnly) {
                    publishScanError(error.localizedMessage?.take(220)
                        ?: getString(R.string.status_error))
                } else {
                    publishStatus(
                        IranVpnContract.STATE_ERROR,
                        error.localizedMessage?.take(220)
                            ?: getString(R.string.status_error)
                    )
                }
                startupJob = null
                stopEngine()
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        stopEngine()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEngine()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureLibboxSetup() {
        if (isLibboxReady) return
        val workingDirectory = getExternalFilesDir(null) ?: filesDir
        Libbox.setup(
            io.nekohasekai.libbox.SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = workingDirectory.absolutePath
                tempPath = cacheDir.absolutePath
                logMaxLines = 3000
                debug = BuildConfig.DEBUG
                appVersion = BuildConfig.VERSION_CODE.toString()
                appMarketingVersion = BuildConfig.VERSION_NAME
            }
        )
        isLibboxReady = true
    }

    private fun createOverrideOptions(): OverrideOptions = OverrideOptions().apply {
        if (selectedPackages.isNotEmpty()) {
            includePackage = StringArray((selectedPackages + packageName).distinct().iterator())
        }
    }

    private fun stopEngine() {
        startupJob?.cancel()
        startupJob = null
        activeServerMonitorJob?.cancel()
        activeServerMonitorJob = null
        closeDefaultNetworkMonitor()
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunFileDescriptor?.close() }
        tunFileDescriptor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun publishStatus(
        state: String,
        message: String,
        activeOutboundTag: String? = null
    ) {
        val intent = Intent(IranVpnContract.ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(IranVpnContract.EXTRA_STATE, state)
            .putExtra(IranVpnContract.EXTRA_MESSAGE, message)
        if (!activeOutboundTag.isNullOrBlank()) {
            intent.putExtra(IranVpnContract.EXTRA_ACTIVE_TAG, activeOutboundTag)
        }
        sendBroadcast(intent)
    }

    private data class ServerHealthResult(
        val tag: String,
        val name: String,
        val host: String,
        val port: Int,
        val pingMs: Int,
        val speedMbps: Double,
        val successRate: Double = 1.0,
        val jitterMs: Int = 0
    )

    private fun readControllerSecret(config: String): String? = runCatching {
        JSONObject(config)
            .getJSONObject("experimental")
            .getJSONObject("clash_api")
            .getString("secret")
    }.getOrNull()

    private suspend fun awaitControllerReady(secret: String) {
        repeat(CONTROLLER_READY_ATTEMPTS) { attempt ->
            val ready = runCatching {
                val selector = callController(secret, "/proxies/proxy")
                selector.optString("name") == "proxy"
            }.getOrDefault(false)
            if (ready) return
            if (attempt == CONTROLLER_READY_ATTEMPTS - 1) {
                error("The local VPN controller did not become ready.")
            }
            kotlinx.coroutines.delay(CONTROLLER_READY_INTERVAL_MS)
        }
    }

    private suspend fun checkAllServers(
        secret: String,
        config: String,
        runSpeedTest: Boolean = true
    ): List<ServerHealthResult> {
        val candidates = readServerOutbounds(config)
        if (candidates.isEmpty()) error(getString(R.string.iran_profile_missing))

        candidates.forEach { outbound ->
            publishServerHealth(outbound.tag, -1, -1.0, "checking")
        }

        // Test all candidates concurrently, with a conservative limit to avoid
        // overloading the phone or the current network.
        val semaphore = Semaphore(MAX_PARALLEL_PING_TESTS)
        val latencyResults = coroutineScope {
            candidates.map { outbound ->
                async {
                    semaphore.withPermit {
                        val ping = runCatching { testOutboundLatency(secret, outbound.tag) }
                            .getOrDefault(-1)
                        ServerHealthResult(
                            tag = outbound.tag,
                            name = outbound.name,
                            host = outbound.host,
                            port = outbound.port,
                            pingMs = ping,
                            speedMbps = -1.0
                        )
                    }
                }
            }.awaitAll()
        }

        val measured = latencyResults.toMutableList()
        latencyResults.forEach { result ->
            publishServerHealth(
                result.tag,
                result.pingMs,
                result.speedMbps,
                if (result.pingMs > 0) "online" else "offline"
            )
        }

        // A quick scan only needs to prove reachability. More expensive quality
        // sampling is reserved for a connection attempt.
        if (!runSpeedTest) return measured

        // Repeat probes only for the four best responders. This provides a
        // lightweight loss/jitter estimate without probing every node repeatedly.
        val stabilityCandidates = measured
            .filter { it.pingMs > 0 }
            .sortedBy { it.pingMs }
            .take(MAX_STABILITY_PROBE_CANDIDATES)
        val stabilitySemaphore = Semaphore(MAX_PARALLEL_STABILITY_PROBES)
        val stabilitySamples = coroutineScope {
            stabilityCandidates.map { candidate ->
                async {
                    stabilitySemaphore.withPermit {
                        val extra = (0 until STABILITY_REPEAT_PROBES).mapNotNull {
                            runCatching { testOutboundLatency(secret, candidate.tag) }
                                .getOrNull()
                                ?.takeIf { it > 0 }
                        }
                        candidate.tag to extra
                    }
                }
            }.awaitAll().toMap()
        }

        for (index in measured.indices) {
            val current = measured[index]
            if (current.pingMs <= 0) continue
            val samples = listOf(current.pingMs) + stabilitySamples[current.tag].orEmpty()
            val averagePing = samples.sum() / samples.size
            val successRate = samples.size.toDouble() / (STABILITY_REPEAT_PROBES + 1)
            val jitter = if (samples.size > 1) samples.maxOrNull()!! - samples.minOrNull()!! else 0
            val updated = current.copy(
                pingMs = averagePing,
                successRate = successRate,
                jitterMs = jitter
            )
            measured[index] = updated
            publishServerHealth(updated.tag, updated.pingMs, -1.0, "online")
        }

        // Only sample real throughput for the three strongest responders. This
        // caps startup traffic and prevents a long serial test of every free node.
        val speedCandidates = measured
            .filter { it.pingMs > 0 }
            .sortedWith(
                compareBy<ServerHealthResult> { it.pingMs }
                    .thenByDescending { it.successRate }
                    .thenBy { it.jitterMs }
            )
            .take(MAX_SPEED_TEST_CANDIDATES)
            .map { it.tag }
            .toSet()

        for (index in measured.indices) {
            val current = measured[index]
            if (current.pingMs <= 0 || current.tag !in speedCandidates) continue
            val speed = runCatching {
                selectOutbound(secret, current.tag)
                measureDownloadMbps()
            }.getOrDefault(-1.0)
            val updated = current.copy(speedMbps = speed)
            measured[index] = updated
            publishServerHealth(
                current.tag,
                current.pingMs,
                speed,
                if (speed > 0) "online" else "reachable"
            )
        }
        return measured
    }

    private data class ServerOutbound(
        val tag: String,
        val name: String,
        val host: String,
        val port: Int
    )

    private fun readServerOutbounds(config: String): List<ServerOutbound> {
        val outbounds = JSONObject(config).optJSONArray("outbounds") ?: return emptyList()
        return buildList {
            for (index in 0 until outbounds.length()) {
                val item = outbounds.optJSONObject(index) ?: continue
                val tag = item.optString("tag")
                if (item.optString("type") != "vless" || !tag.startsWith("node-")) continue
                val host = item.optString("server")
                if (host.isBlank()) continue
                add(
                    ServerOutbound(
                        tag = tag,
                        name = tag,
                        host = host,
                        port = item.optInt("server_port", 443)
                    )
                )
            }
        }
    }

    private fun testOutboundLatency(secret: String, tag: String): Int {
        INTERNET_TEST_URLS.forEach { testUrl ->
            val delayMs = runCatching {
                val url = URLEncoder.encode(testUrl, "UTF-8")
                callController(
                    secret,
                    "/proxies/$tag/delay?timeout=$PING_TEST_TIMEOUT_MS&url=$url"
                ).optInt("delay", -1)
            }.getOrDefault(-1)
            if (delayMs > 0) return delayMs
        }
        return -1
    }

    private fun selectOutbound(secret: String, tag: String) {
        callController(
            secret,
            "/proxies/proxy",
            method = "PUT",
            body = JSONObject().put("name", tag)
        )
    }

    private fun callController(
        secret: String,
        path: String,
        method: String = "GET",
        body: JSONObject? = null
    ): JSONObject {
        val connection = (URL("$CONTROLLER_BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONTROLLER_TIMEOUT_MS
            readTimeout = CONTROLLER_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $secret")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                throw java.io.IOException("Local VPN controller returned HTTP $status. $detail")
            }
            if (status == HttpURLConnection.HTTP_NO_CONTENT) return JSONObject()
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun measureDownloadMbps(): Double {
        val proxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress(LOOPBACK_SPEED_PROXY_HOST, LOOPBACK_SPEED_PROXY_PORT)
        )
        val connection = (URL(SPEED_TEST_URL).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = SPEED_TEST_TIMEOUT_MS
            readTimeout = SPEED_TEST_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "RaahatVPN/${BuildConfig.VERSION_NAME}")
        }
        val startedAt = System.nanoTime()
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw java.io.IOException("Speed test returned HTTP $status")
            var totalBytes = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (totalBytes < SPEED_TEST_MAX_BYTES) {
                    val count = input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), SPEED_TEST_MAX_BYTES - totalBytes).toInt()
                    )
                    if (count < 0) break
                    totalBytes += count
                }
            }
            val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
            if (totalBytes < SPEED_TEST_MIN_BYTES || elapsedSeconds <= 0.0) {
                throw java.io.IOException("Speed test response was too small.")
            }
            totalBytes * 8.0 / elapsedSeconds / 1_000_000.0
        } finally {
            connection.disconnect()
        }
    }

    private fun selectBestServer(results: List<ServerHealthResult>): ServerHealthResult? {
        val selectedTag = ServerHealthScorer.selectBest(
            results.map { result ->
                ServerQualitySample(
                    tag = result.tag,
                    pingMs = result.pingMs,
                    speedMbps = result.speedMbps,
                    successRate = result.successRate,
                    jitterMs = result.jitterMs
                )
            }
        )
        return results.firstOrNull { it.tag == selectedTag }
    }

    private fun createHealthScanConfig(config: String): String {
        val root = JSONObject(config)
        val originalInbounds = root.optJSONArray("inbounds")
            ?: error("The VPN profile has no inbounds to test.")
        val scanInbounds = JSONArray()
        for (index in 0 until originalInbounds.length()) {
            val inbound = originalInbounds.optJSONObject(index) ?: continue
            if (inbound.optString("type") != "tun") scanInbounds.put(inbound)
        }
        check(scanInbounds.length() > 0) { "The local proxy inbound is missing from the test profile." }
        root.put("inbounds", scanInbounds)
        return root.toString()
    }

    private fun publishScanComplete(onlineCount: Int, totalCount: Int) {
        sendBroadcast(
            Intent(IranVpnContract.ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(IranVpnContract.EXTRA_STATE, IranVpnContract.STATE_SCAN_COMPLETE)
                .putExtra(IranVpnContract.EXTRA_SCAN_ONLINE_COUNT, onlineCount)
                .putExtra(IranVpnContract.EXTRA_SCAN_TOTAL_COUNT, totalCount)
        )
    }

    private fun publishScanError(message: String) {
        sendBroadcast(
            Intent(IranVpnContract.ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(IranVpnContract.EXTRA_STATE, IranVpnContract.STATE_SCAN_ERROR)
                .putExtra(IranVpnContract.EXTRA_MESSAGE, message)
        )
    }

    private fun publishServerHealth(tag: String, pingMs: Int, speedMbps: Double, status: String) {
        sendBroadcast(
            Intent(IranVpnContract.ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(IranVpnContract.EXTRA_STATE, IranVpnContract.STATE_SERVER_HEALTH)
                .putExtra(IranVpnContract.EXTRA_SERVER_TAG, tag)
                .putExtra(IranVpnContract.EXTRA_PING_MS, pingMs)
                .putExtra(IranVpnContract.EXTRA_SPEED_MBPS, speedMbps)
                .putExtra(IranVpnContract.EXTRA_HEALTH_STATUS, status)
        )
    }

    private fun verifyInternetThroughLocalProxy(): Boolean =
        verifyInternetWithProxy(
            Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress(LOOPBACK_SPEED_PROXY_HOST, LOOPBACK_SPEED_PROXY_PORT)
            )
        )

    private fun verifyInternetThroughTun(): Boolean =
        verifyInternetWithProxy(proxy = null)

    private fun verifyInternetWithProxy(proxy: Proxy?): Boolean {
        for (testUrl in INTERNET_TEST_URLS) {
            val passed = runCatching {
                val connection = (
                    if (proxy == null) URL(testUrl).openConnection()
                    else URL(testUrl).openConnection(proxy)
                ) as HttpURLConnection
                connection.apply {
                    connectTimeout = INTERNET_VERIFY_TIMEOUT_MS
                    readTimeout = INTERNET_VERIFY_TIMEOUT_MS
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("User-Agent", "RaahatVPN/${BuildConfig.VERSION_NAME}")
                }.let { request ->
                    try {
                        request.responseCode in 200..299
                    } finally {
                        request.disconnect()
                    }
                }
            }.onFailure {
                Log.d(TAG, "Internet probe failed for $testUrl", it)
            }.getOrDefault(false)
            if (passed) return true
        }
        return false
    }

    private fun startActiveServerMonitor(
        secret: String,
        initialResults: List<ServerHealthResult>,
        initialTag: String
    ) {
        activeServerMonitorJob?.cancel()
        activeServerMonitorJob = serviceScope.launch {
            var candidates = initialResults
            var activeTag = initialTag
            var consecutiveFailures = 0

            while (isActive) {
                kotlinx.coroutines.delay(ACTIVE_SERVER_POLL_INTERVAL_MS)

                val ping = runCatching { testOutboundLatency(secret, activeTag) }
                    .getOrDefault(-1)
                if (ping > 0) {
                    consecutiveFailures = 0
                    val current = candidates.firstOrNull { it.tag == activeTag }
                    val speed = current?.speedMbps ?: -1.0
                    publishServerHealth(activeTag, ping, speed, "online")
                    continue
                }

                publishServerHealth(activeTag, -1, -1.0, "offline")
                consecutiveFailures++
                if (consecutiveFailures < ACTIVE_SERVER_FAILURES_BEFORE_SWITCH) continue

                val config = currentConfig ?: continue
                val refreshed = runCatching {
                    checkAllServers(secret, config, runSpeedTest = false)
                }.getOrDefault(emptyList())
                val merged = refreshed.map { fresh ->
                    val previous = candidates.firstOrNull { it.tag == fresh.tag }
                    fresh.copy(
                        speedMbps = previous?.speedMbps ?: -1.0,
                        successRate = fresh.successRate,
                        jitterMs = previous?.jitterMs ?: fresh.jitterMs
                    )
                }

                var remaining = merged.filter { it.tag != activeTag }
                var replacement: ServerHealthResult? = null
                while (remaining.isNotEmpty() && replacement == null) {
                    val next = selectBestServer(remaining) ?: break
                    remaining = remaining.filterNot { it.tag == next.tag }
                    val switched = runCatching {
                        selectOutbound(secret, next.tag)
                        kotlinx.coroutines.delay(350)
                        verifyInternetThroughLocalProxy() && verifyInternetThroughTun()
                    }.getOrDefault(false)
                    if (switched) replacement = next
                }

                if (replacement != null) {
                    val chosen = replacement
                    activeTag = chosen.tag
                    candidates = merged
                    consecutiveFailures = 0
                    publishStatus(
                        IranVpnContract.STATE_CONNECTED,
                        getString(
                            R.string.iran_switching_server,
                            chosen.name,
                            chosen.host,
                            chosen.port
                        ),
                        activeTag
                    )
                    updateForegroundNotification(
                        getString(R.string.iran_connected_to_server, chosen.name, chosen.host, chosen.port)
                    )
                } else {
                    // Do not leave the selector pointing at an unverified trial node.
                    runCatching { selectOutbound(secret, activeTag) }
                    candidates = merged.ifEmpty { candidates }
                    consecutiveFailures = 0
                    updateForegroundNotification(getString(R.string.iran_server_recovery))
                }
            }
        }
    }

    private fun notification(message: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    getString(R.string.iran_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun updateForegroundNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    // CommandServerHandler callbacks.
    override fun serviceStop() {
        publishStatus(IranVpnContract.STATE_DISCONNECTED, getString(R.string.iran_disconnected))
        stopEngine()
        stopSelf()
    }

    override fun serviceReload() {
        val config = currentConfig ?: return
        serviceScope.launch {
            runCatching {
                commandServer?.startOrReloadService(config, createOverrideOptions())
            }.onFailure { error ->
                Log.e(TAG, "Could not reload sing-box service", error)
                publishStatus(IranVpnContract.STATE_ERROR, getString(R.string.iran_engine_error, error.message ?: "Unknown error"))
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply {
            available = false
            enabled = false
        }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message.orEmpty())
    }

    override fun connectSSHAgent(): Int = -1

    // PlatformInterface callbacks.
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) Log.w(TAG, "Could not protect an outbound socket from the VPN tunnel.")
    }

    private fun isUnderlyingNetworkMetered(): Boolean {
        val network = connectivity.activeNetwork ?: return true
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(this) != null) error("Android VPN permission has not been granted.")

        val safeMtu = options.mtu.takeIf { it in MIN_SAFE_MTU..MAX_SAFE_MTU } ?: DEFAULT_TUN_MTU
        val builder = Builder()
            .setSession(getString(R.string.iran_vpn_session))
            .setMtu(safeMtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(isUnderlyingNetworkMetered())
        }

        val ipv4 = options.inet4Address
        val hasIpv4Address = ipv4.hasNext()
        while (ipv4.hasNext()) {
            val address = ipv4.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val ipv6 = options.inet6Address
        val hasIpv6Address = ipv6.hasNext()
        while (ipv6.hasNext()) {
            val address = ipv6.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            val dns = options.dnsServerAddress
            var dnsServerCount = 0
            while (dns.hasNext()) {
                builder.addDnsServer(dns.next())
                dnsServerCount++
            }
            // Prefer the DNS servers from the current underlying network when
            // libbox does not supply any. Fall back to public resolvers only when
            // Android has no usable network DNS addresses.
            if (dnsServerCount == 0) {
                val activeNetwork = connectivity.activeNetwork
                val networkDns = activeNetwork
                    ?.let { connectivity.getLinkProperties(it)?.dnsServers }
                    .orEmpty()
                    .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress?.substringBefore('%')?.takeIf(String::isNotBlank) }
                    .distinct()
                    .take(2)
                val fallbackDns = networkDns.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
                fallbackDns.forEach { dnsServer -> builder.addDnsServer(dnsServer) }
                if (networkDns.isEmpty()) {
                    Log.w(TAG, "No usable DNS servers were reported by Android; installed public DNS fallbacks.")
                } else {
                    Log.i(TAG, "Installed DNS addresses from the active underlying network.")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // sing-box can supply only the interface/endpoint routes on some Android builds.
                // Having any route is not enough: without /0 Android installs a VPN interface
                // but normal Internet destinations never enter it.
                var hasIpv4DefaultRoute = false
                val ipv4Routes = options.inet4RouteAddress
                while (ipv4Routes.hasNext()) {
                    val route = ipv4Routes.next()
                    if (route.prefix() == 0) hasIpv4DefaultRoute = true
                    builder.addRoute(route.toIpPrefix())
                }
                if (hasIpv4Address && !hasIpv4DefaultRoute) {
                    builder.addRoute("0.0.0.0", 0)
                    Log.w(TAG, "Added missing IPv4 default route to Android VPN interface.")
                }

                var hasIpv6DefaultRoute = false
                val ipv6Routes = options.inet6RouteAddress
                while (ipv6Routes.hasNext()) {
                    val route = ipv6Routes.next()
                    if (route.prefix() == 0) hasIpv6DefaultRoute = true
                    builder.addRoute(route.toIpPrefix())
                }
                if (hasIpv6Address && !hasIpv6DefaultRoute) {
                    builder.addRoute("::", 0)
                    Log.w(TAG, "Added missing IPv6 default route to Android VPN interface.")
                }

                val excluded4 = options.inet4RouteExcludeAddress
                while (excluded4.hasNext()) builder.excludeRoute(excluded4.next().toIpPrefix())
                val excluded6 = options.inet6RouteExcludeAddress
                while (excluded6.hasNext()) builder.excludeRoute(excluded6.next().toIpPrefix())
            } else {
                var hasIpv4DefaultRoute = false
                val ipv4Routes = options.inet4RouteRange
                while (ipv4Routes.hasNext()) {
                    val range = ipv4Routes.next()
                    if (range.prefix() == 0) hasIpv4DefaultRoute = true
                    builder.addRoute(range.address(), range.prefix())
                }
                if (hasIpv4Address && !hasIpv4DefaultRoute) {
                    builder.addRoute("0.0.0.0", 0)
                    Log.w(TAG, "Added missing IPv4 default route to Android VPN interface.")
                }

                var hasIpv6DefaultRoute = false
                val ipv6Routes = options.inet6RouteRange
                while (ipv6Routes.hasNext()) {
                    val range = ipv6Routes.next()
                    if (range.prefix() == 0) hasIpv6DefaultRoute = true
                    builder.addRoute(range.address(), range.prefix())
                }
                if (hasIpv6Address && !hasIpv6DefaultRoute) {
                    builder.addRoute("::", 0)
                    Log.w(TAG, "Added missing IPv6 default route to Android VPN interface.")
                }
            }

            val include = options.includePackage
            while (include.hasNext()) {
                val app = include.next()
                runCatching { builder.addAllowedApplication(app) }
                    .onFailure { Log.w(TAG, "Could not allow application $app", it) }
            }
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                val app = exclude.next()
                runCatching { builder.addDisallowedApplication(app) }
                    .onFailure { Log.w(TAG, "Could not exclude application $app", it) }
            }
        }

        val descriptor = builder.establish()
            ?: error("Android could not establish the VPN interface.")
        runCatching { tunFileDescriptor?.close() }
        tunFileDescriptor = descriptor
        return descriptor.fd
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ConnectionOwner()
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )
        if (uid == Process.INVALID_UID) return ConnectionOwner()
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(StringArray(packages.iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        closeDefaultNetworkMonitor()
        defaultInterfaceListener = listener
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateDefaultInterface(network)
            }

            override fun onLost(network: Network) {
                val current = connectivity.activeNetwork
                if (current == null || current == network) {
                    listener.updateDefaultInterface("", -1, false, false)
                } else {
                    updateDefaultInterface(current)
                }
            }
        }
        networkCallback = callback
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            connectivity.activeNetwork?.let(::updateDefaultInterface)
        }.onFailure {
            Log.w(TAG, "Could not monitor Android's default network", it)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        closeDefaultNetworkMonitor()
    }

    private fun closeDefaultNetworkMonitor() {
        networkCallback?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        defaultInterfaceListener = null
    }

    private fun updateDefaultInterface(network: Network) {
        val listener = defaultInterfaceListener ?: return
        val linkProperties = connectivity.getLinkProperties(network) ?: return
        val name = linkProperties.interfaceName ?: return
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        if (index >= 0) {
            val capabilities = connectivity.getNetworkCapabilities(network)
            val metered = capabilities == null ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            listener.updateDefaultInterface(name, index, metered, false)
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        val networkInterfaces = runCatching {
            val enumeration = NetworkInterface.getNetworkInterfaces()
            if (enumeration == null) emptyList() else Collections.list(enumeration)
        }.getOrDefault(emptyList())

        for (network in connectivity.allNetworks) {
            val linkProperties = connectivity.getLinkProperties(network) ?: continue
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            val name = linkProperties.interfaceName ?: continue
            val systemInterface = networkInterfaces.firstOrNull { it.name == name } ?: continue
            val boxInterface = LibboxNetworkInterface().apply {
                this.name = name
                dnsServer = StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator())
                gateway = StringArray(
                    linkProperties.routes
                        .filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway?.hostAddress }
                        .iterator()
                )
                type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                index = systemInterface.index
                mtu = runCatching { systemInterface.mtu }.getOrDefault(1500)
                addresses = StringArray(systemInterface.interfaceAddresses.map { it.toPrefix() }.iterator())
                flags = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                } else {
                    0
                }
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            interfaces.add(boxInterface)
        }

        return object : NetworkInterfaceIterator {
            private val iterator = interfaces.iterator()
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): LibboxNetworkInterface = iterator.next()
        }
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = Unit

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession = throw UnsupportedOperationException("Platform shell is not available.")

    override fun readSystemSSHHostKey(): String =
        throw UnsupportedOperationException("System SSH host keys are not available.")
    override fun lookupSFTPServer(): String = ""
    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession =
        throw UnsupportedOperationException("Platform bridge is not available.")
    override fun lookupUser(username: String?): PlatformUser =
        throw UnsupportedOperationException("Platform user lookup is not available.")
    override fun registerMyInterface(name: String?) = Unit

    override fun sendNotification(notification: LibboxNotification) {
        Log.i(TAG, notification.body.orEmpty())
    }

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    private class StringArray(private val values: List<String>) : StringIterator {
        constructor(iterator: Iterator<String>) : this(iterator.asSequence().toList())

        private var index = 0

        override fun len(): Int = values.size
        override fun hasNext(): Boolean = index < values.size
        override fun next(): String = values[index++]
    }

    private fun InterfaceAddress.toPrefix(): String {
        val address = address.hostAddress.substringBefore('%')
        return "${address}/$networkPrefixLength"
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun io.nekohasekai.libbox.RoutePrefix.toIpPrefix(): android.net.IpPrefix =
        android.net.IpPrefix(InetAddress.getByName(address()), prefix())

    private companion object {
        const val TAG = "RaahatIranVpn"
        const val NOTIFICATION_ID = 2405
        const val NOTIFICATION_CHANNEL = "raahat_iran_vpn"
        const val EXTRA_CONFIG = "config_json"
        const val EXTRA_PACKAGES = "selected_packages"
        const val EXTRA_SELECTED_OUTBOUND = "selected_outbound_tag"
        const val EXTRA_SCAN_ONLY = "scan_only"
        const val AUTO_OUTBOUND_TAG = "auto"
        const val ACTION_STOP = "com.example.onetapvpn.iran.STOP"
        const val CONTROLLER_BASE_URL = "http://127.0.0.1:19090"
        const val CONTROLLER_TIMEOUT_MS = 8_000
        const val CONTROLLER_READY_ATTEMPTS = 20
        const val CONTROLLER_READY_INTERVAL_MS = 500L
        const val PING_TEST_URL = "https://cp.cloudflare.com/generate_204"
        const val FALLBACK_PING_TEST_URL = "https://connectivitycheck.gstatic.com/generate_204"
        val INTERNET_TEST_URLS = listOf(PING_TEST_URL, FALLBACK_PING_TEST_URL)
        const val PING_TEST_TIMEOUT_MS = 6_000
        const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=524288"
        const val SPEED_TEST_TIMEOUT_MS = 6_000
        const val SPEED_TEST_MAX_BYTES = 128L * 1024L
        const val SPEED_TEST_MIN_BYTES = 32L * 1024L
        const val INTERNET_VERIFY_TIMEOUT_MS = 8_000
        const val LOOPBACK_SPEED_PROXY_HOST = "127.0.0.1"
        const val LOOPBACK_SPEED_PROXY_PORT = 2080
        const val MAX_PARALLEL_PING_TESTS = 6
        const val MAX_PARALLEL_STABILITY_PROBES = 2
        const val MAX_STABILITY_PROBE_CANDIDATES = 4
        const val STABILITY_REPEAT_PROBES = 2
        const val MAX_SPEED_TEST_CANDIDATES = 3
        const val ACTIVE_SERVER_POLL_INTERVAL_MS = 10_000L
        const val ACTIVE_SERVER_FAILURES_BEFORE_SWITCH = 2
        const val MIN_SAFE_MTU = 1280
        const val MAX_SAFE_MTU = 1500
        const val DEFAULT_TUN_MTU = 1400

        @Volatile
        private var isLibboxReady = false
    }
}
