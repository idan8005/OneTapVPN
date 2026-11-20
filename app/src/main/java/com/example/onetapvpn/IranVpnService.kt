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
        selectedPackages = intent?.getStringArrayListExtra(EXTRA_PACKAGES)
            ?.distinct()
            ?.filter { it.isNotBlank() }
            .orEmpty()
        selectedOutboundTag = intent?.getStringExtra(EXTRA_SELECTED_OUTBOUND)
            ?.takeIf { it.isNotBlank() }
            ?: AUTO_OUTBOUND_TAG
        currentConfig = config

        startupJob?.cancel()
        startupJob = serviceScope.launch {
            try {
                ensureLibboxSetup()
                val server = commandServer ?: CommandServer(this@IranVpnService, this@IranVpnService).also {
                    it.start()
                    commandServer = it
                }
                server.startOrReloadService(config, createOverrideOptions())

                val secret = readControllerSecret(config)
                    ?: error("The local server-health controller is not configured.")
                awaitControllerReady(secret)
                updateForegroundNotification(getString(R.string.iran_server_health_checking))
                val healthResults = checkAllServers(secret, config)
                val available = healthResults.filter { it.pingMs > 0 }
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
                startActiveServerMonitor(secret)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "VPN startup or Internet verification failed", error)
                publishStatus(
                    IranVpnContract.STATE_ERROR,
                    error.localizedMessage?.take(220)
                        ?: getString(R.string.status_error)
                )
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
        val speedMbps: Double
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

    private suspend fun checkAllServers(secret: String, config: String): List<ServerHealthResult> {
        val candidates = readServerOutbounds(config)
        if (candidates.isEmpty()) error(getString(R.string.iran_profile_missing))

        candidates.forEach { outbound ->
            publishServerHealth(outbound.tag, -1, -1.0, "checking")
        }

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

        // A small, fixed download compares real proxied throughput without
        // turning the check into a large data-consuming speed test.
        for (index in measured.indices) {
            val current = measured[index]
            if (current.pingMs <= 0) continue
            val speed = runCatching {
                selectOutbound(secret, current.tag)
                measureDownloadMbps()
            }.getOrDefault(-1.0)
            measured[index] = current.copy(speedMbps = speed)
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
        val online = results.filter { it.pingMs > 0 }
        if (online.isEmpty()) return null
        val measuredSpeeds = online.filter { it.speedMbps > 0.0 }
        if (measuredSpeeds.isEmpty()) return online.minByOrNull { it.pingMs }

        val fastestMbps = measuredSpeeds.maxOf { it.speedMbps }
        val lowestPing = online.minOf { it.pingMs }.coerceAtLeast(1)
        return measuredSpeeds.maxByOrNull { candidate ->
            val speedScore = (candidate.speedMbps / fastestMbps).coerceIn(0.0, 1.0)
            val latencyScore = (lowestPing.toDouble() / candidate.pingMs).coerceIn(0.0, 1.0)
            speedScore * SPEED_SCORE_WEIGHT + latencyScore * LATENCY_SCORE_WEIGHT
        }
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

    private fun startActiveServerMonitor(secret: String) {
        activeServerMonitorJob?.cancel()
        activeServerMonitorJob = serviceScope.launch {
            repeat(ACTIVE_SERVER_INITIAL_POLLS) {
                val tag = readActiveOutboundTag(secret)
                if (!tag.isNullOrBlank()) {
                    publishStatus(
                        IranVpnContract.STATE_CONNECTED,
                        getString(R.string.iran_active_server_updated),
                        tag
                    )
                }
                kotlinx.coroutines.delay(ACTIVE_SERVER_POLL_INTERVAL_MS)
            }
            while (isActive) {
                kotlinx.coroutines.delay(ACTIVE_SERVER_POLL_INTERVAL_MS)
                val tag = readActiveOutboundTag(secret)
                if (!tag.isNullOrBlank()) {
                    publishStatus(
                        IranVpnContract.STATE_CONNECTED,
                        getString(R.string.iran_active_server_updated),
                        tag
                    )
                }
            }
        }
    }

    private fun readActiveOutboundTag(secret: String): String? = runCatching {
        val selectedGroup = callController(secret, "/proxies/proxy").optString("now")
        if (selectedGroup == AUTO_OUTBOUND_TAG) {
            callController(secret, "/proxies/auto").optString("now")
        } else {
            selectedGroup
        }
    }.getOrNull()?.takeIf { it.startsWith("node-") }

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

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(this) != null) error("Android VPN permission has not been granted.")

        val builder = Builder()
            .setSession(getString(R.string.iran_vpn_session))
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

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
            // Avoid creating a VPN interface without any DNS servers on devices where
            // libbox has no Android DNS addresses to hand to VpnService.Builder.
            if (dnsServerCount == 0) {
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
                Log.w(TAG, "sing-box supplied no DNS addresses; installed public DNS fallback addresses.")
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
        if (index >= 0) listener.updateDefaultInterface(name, index, false, false)
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
        const val SPEED_TEST_TIMEOUT_MS = 8_000
        const val SPEED_TEST_MAX_BYTES = 256L * 1024L
        const val SPEED_TEST_MIN_BYTES = 64L * 1024L
        const val INTERNET_VERIFY_TIMEOUT_MS = 8_000
        const val LOOPBACK_SPEED_PROXY_HOST = "127.0.0.1"
        const val LOOPBACK_SPEED_PROXY_PORT = 2080
        const val MAX_PARALLEL_PING_TESTS = 4
        const val SPEED_SCORE_WEIGHT = 0.65
        const val LATENCY_SCORE_WEIGHT = 0.35
        const val ACTIVE_SERVER_POLL_INTERVAL_MS = 5_000L
        const val ACTIVE_SERVER_INITIAL_POLLS = 20

        @Volatile
        private var isLibboxReady = false
    }
}
