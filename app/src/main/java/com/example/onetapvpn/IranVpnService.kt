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

        serviceScope.launch {
            try {
                ensureLibboxSetup()
                val server = commandServer ?: CommandServer(this@IranVpnService, this@IranVpnService).also {
                    it.start()
                    commandServer = it
                }
                server.startOrReloadService(config, createOverrideOptions())

                val secret = readControllerSecret(config)
                    ?: error("The local server-health controller is not configured.")
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

                val chosen = healthResults.first { it.tag == chosenTag }
                publishStatus(
                    IranVpnContract.STATE_CONNECTED,
                    getString(R.string.iran_connected_verified),
                    chosenTag
                )
                updateForegroundNotification(
                    getString(R.string.iran_connected_to_server, chosen.name, chosen.host, chosen.port)
                )
                startActiveServerMonitor(config, secret)
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

    private fun startActiveServerMonitor(config: String) {
        activeServerMonitorJob?.cancel()
        val secret = runCatching {
            JSONObject(config)
                .getJSONObject("experimental")
                .getJSONObject("clash_api")
                .getString("secret")
        }.getOrNull() ?: return

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

    private fun readActiveOutboundTag(secret: String): String? {
        val connection = (URL(ACTIVE_SERVER_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = ACTIVE_SERVER_API_TIMEOUT_MS
            readTimeout = ACTIVE_SERVER_API_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $secret")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(response).optString("now").takeIf { it.isNotBlank() && it != "null" }
        } catch (error: Exception) {
            Log.d(TAG, "Automatic server selection is not available yet", error)
            null
        } finally {
            connection.disconnect()
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
        const val ACTION_STOP = "com.example.onetapvpn.iran.STOP"
        const val ACTIVE_SERVER_API_URL = "http://127.0.0.1:19090/proxies/auto"
        const val ACTIVE_SERVER_API_TIMEOUT_MS = 1_000
        const val ACTIVE_SERVER_POLL_INTERVAL_MS = 5_000L
        const val ACTIVE_SERVER_INITIAL_POLLS = 20

        @Volatile
        private var isLibboxReady = false
    }
}
