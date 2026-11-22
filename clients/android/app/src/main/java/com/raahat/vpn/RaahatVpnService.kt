package com.raahat.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import java.io.File
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.NetworkInterface as JavaNetworkInterface
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RaahatVpnService : VpnService() {
    companion object {
        const val ACTION_STATE = "com.raahat.vpn.STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"

        private const val ACTION_START = "com.raahat.vpn.START"
        private const val ACTION_STOP = "com.raahat.vpn.STOP"
        private const val EXTRA_CONFIG = "config"
        private const val CHANNEL_ID = "raahat-vpn-connection"
        private const val NOTIFICATION_ID = 4101
        private const val PREFS = "raahat_connection"
        private const val DNS_PROBE_HOST = "cloudflare.com"
        private const val IPV4_PROBE_URL = "https://ipv4.icanhazip.com/"
        private const val IPV6_PROBE_URL = "https://ipv6.icanhazip.com/"
        private const val HEALTH_PROBE_TIMEOUT_MS = 4500
        private const val MAX_HEALTH_RESPONSE_BYTES = 128

        fun start(context: Context, singBoxConfig: String) {
            val intent = Intent(context, RaahatVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, singBoxConfig)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RaahatVpnService::class.java).setAction(ACTION_STOP))
        }

        private fun saveState(context: Context, state: ConnectionState, message: String? = null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(EXTRA_STATE, state.name)
                .putString(EXTRA_MESSAGE, message.orEmpty())
                .apply()
            context.sendBroadcast(
                Intent(ACTION_STATE)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_STATE, state.name)
                    .putExtra(EXTRA_MESSAGE, message.orEmpty())
            )
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val tunOpened = AtomicBoolean(false)
    private val engineStopped = AtomicBoolean(true)
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var commandServer: CommandServer? = null
    private var activeConfig: String = ""
    private var platformHandler: AndroidPlatformHandler? = null
    private var setupDone = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                worker.execute { stopEngine(ConnectionState.DISCONNECTED, null) }
                return Service.START_NOT_STICKY
            }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    saveState(this, ConnectionState.FAILED, "پیکربندی سرور دریافت نشد.")
                    stopSelf(startId)
                    return Service.START_NOT_STICKY
                }
                engineStopped.set(false)
                activeConfig = config
                saveState(this, ConnectionState.CONNECTING, "در حال برقراری تونل امن…")
                startAsForeground("در حال اتصال به سرور…")
                worker.execute { startEngine(config) }
            }
            else -> return Service.START_NOT_STICKY
        }
        return Service.START_NOT_STICKY
    }

    private fun startAsForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "وضعیت اتصال راحت وی‌پی‌ان", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Raahat VPN")
            .setContentText(text)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Raahat VPN")
            .setContentText(text)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun startEngine(config: String) {
        try {
            if (!setupDone) {
                val base = filesDir
                val work = File(getExternalFilesDir(null) ?: filesDir, "raahat-work").apply { mkdirs() }
                val temp = File(cacheDir, "raahat-temp").apply { mkdirs() }
                Libbox.setup(
                    SetupOptions().apply {
                        basePath = base.absolutePath
                        workingPath = work.absolutePath
                        tempPath = temp.absolutePath
                        logMaxLines = 500
                        debug = false
                        appVersion = BuildConfig.VERSION_CODE.toString()
                        appMarketingVersion = BuildConfig.VERSION_NAME
                    }
                )
                runCatching { Libbox.setLocale("fa-IR") }
                setupDone = true
            }

            Libbox.checkConfig(config)
            tunOpened.set(false)
            platformHandler = AndroidPlatformHandler()
            val platform = Proxy.newProxyInstance(
                PlatformInterface::class.java.classLoader,
                arrayOf(PlatformInterface::class.java),
                platformHandler
            ) as PlatformInterface

            val serverHandler = object : CommandServerHandler {
                override fun serviceStop() {
                    worker.execute { stopEngine(ConnectionState.DISCONNECTED, null) }
                }

                override fun serviceReload() {
                    val current = activeConfig
                    if (current.isNotBlank()) {
                        worker.execute {
                            runCatching {
                                commandServer?.startOrReloadService(current, OverrideOptions())
                            }.onFailure {
                                Log.w("RaahatVPN", "Service reload failed (" + it.javaClass.simpleName + ")")
                                stopEngine(ConnectionState.FAILED, "اتصال VPN دوباره راه‌اندازی نشد.")
                            }
                        }
                    }
                }

                override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

                override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

                override fun triggerNativeCrash() = Unit

                override fun writeDebugMessage(message: String?) {
                    // Do not persist engine messages: profiles may contain private connection data.
                }

                override fun connectSSHAgent(): Int = -1
            }

            commandServer = CommandServer(serverHandler, platform)
            commandServer?.start()
            commandServer?.startOrReloadService(config, OverrideOptions())

            if (!tunOpened.get()) {
                throw IllegalStateException("The engine did not establish the Android TUN interface.")
            }
            val healthReport = verifyTunnelTraffic()
            if (!healthReport.isReady) {
                stopEngine(ConnectionState.FAILED, healthReport.toPersianMessage())
                return
            }

            saveState(this, ConnectionState.CONNECTED, healthReport.toPersianMessage())
            updateNotification("اتصال امن برقرار است")
        } catch (error: Exception) {
            Log.w("RaahatVPN", "VPN start failed (" + error.javaClass.simpleName + ")")
            stopEngine(ConnectionState.FAILED, "اتصال برقرار نشد. لینک سرور یا دسترسی شبکه را بررسی کنید.")
        }
    }

    private fun verifyTunnelTraffic(): TunnelHealthReport {
        if (!tunOpened.get()) return TunnelHealthPolicy.evaluate(
            dnsResolved = false,
            ipv4Address = null,
            ipv6Address = null
        )

        // This lookup uses Android's active VPN DNS path. The HTTPS probes below
        // independently verify IPv4 and IPv6 reachability through the established tunnel.
        val dnsResolved = runCatching {
            InetAddress.getAllByName(DNS_PROBE_HOST).isNotEmpty()
        }.getOrDefault(false)

        val ipv4Address = requestProbeAddress(IPV4_PROBE_URL)
        val ipv6Address = requestProbeAddress(IPV6_PROBE_URL)
        return TunnelHealthPolicy.evaluate(dnsResolved, ipv4Address, ipv6Address)
    }

    private fun requestProbeAddress(endpoint: String): String? {
        val connection = runCatching {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = HEALTH_PROBE_TIMEOUT_MS
                readTimeout = HEALTH_PROBE_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
            }
        }.getOrNull() ?: return null

        return try {
            if (connection.responseCode !in 200..299) return null
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(64)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_HEALTH_RESPONSE_BYTES) return null
                    output.write(buffer, 0, count)
                }
            }
            output.toString(StandardCharsets.UTF_8.name()).trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun stopEngine(state: ConnectionState, message: String?) {
        if (!engineStopped.compareAndSet(false, true)) return
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        platformHandler?.closeMonitor()
        platformHandler = null
        tunOpened.set(false)
        activeConfig = ""
        saveState(this, state, message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onRevoke() {
        worker.execute { stopEngine(ConnectionState.DISCONNECTED, "مجوز VPN از تنظیمات دستگاه لغو شد.") }
        super.onRevoke()
    }

    override fun onDestroy() {
        if (!worker.isShutdown) {
            if (!engineStopped.get()) {
                runCatching { worker.execute { stopEngine(ConnectionState.DISCONNECTED, null) } }
            }
            worker.shutdown()
        }
        super.onDestroy()
    }

    private inner class AndroidPlatformHandler : InvocationHandler {
        private val connectivity = getSystemService(ConnectivityManager::class.java)
        private var callback: ConnectivityManager.NetworkCallback? = null

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val values = args ?: emptyArray()
            return when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    val fd = values.firstOrNull() as? Int ?: -1
                    if (fd < 0 || !protect(fd)) error("Could not protect sing-box socket from VPN routing.")
                    null
                }
                "openTun" -> openTun(values[0] as TunOptions)
                "startDefaultInterfaceMonitor" -> {
                    startMonitor(values[0] as InterfaceUpdateListener)
                    null
                }
                "closeDefaultInterfaceMonitor" -> {
                    closeMonitor()
                    null
                }
                "getInterfaces" -> getInterfaces()
                "underNetworkExtension", "includeAllNetworks" -> false
                "readWIFIState", "localDNSTransport" -> null
                "clearDNSCache", "sendNotification", "cancelNotification",
                "startNeighborMonitor", "closeNeighborMonitor", "registerMyInterface" -> null
                "usePlatformShell", "usePlatformBridge", "usePlatformAutoRedirect" -> false
                "tailscaleHostname" -> Build.MANUFACTURER + " " + Build.MODEL
                "findConnectionOwner" -> ConnectionOwner()
                "checkPlatformShell" -> null
                "openShellSession", "lookupSFTPServer", "readSystemSSHHostKey",
                "createBridge", "createAutoRedirect" -> throw UnsupportedOperationException("Platform feature is not enabled.")
                "toString" -> "RaahatAndroidPlatformInterface"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === values.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        }

        private fun openTun(options: TunOptions): Int {
            if (VpnService.prepare(this@RaahatVpnService) != null) {
                error("Android VPN permission is not granted.")
            }
            val builder = Builder()
                .setSession("Raahat VPN")
                .setMtu(options.mtu.coerceIn(576, 9000))

            val inet4 = options.inet4Address
            while (inet4.hasNext()) {
                val prefix = inet4.next()
                builder.addAddress(prefix.address(), prefix.prefix())
            }
            val inet6 = options.inet6Address
            while (inet6.hasNext()) {
                val prefix = inet6.next()
                builder.addAddress(prefix.address(), prefix.prefix())
            }

            if (options.autoRoute) {
                if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                    val dns = options.dnsServerAddress
                    while (dns.hasNext()) builder.addDnsServer(dns.next())
                }

                val route4 = options.inet4RouteAddress
                if (route4.hasNext()) {
                    while (route4.hasNext()) {
                        val prefix = route4.next()
                        builder.addRoute(prefix.address(), prefix.prefix())
                    }
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val route6 = options.inet6RouteAddress
                if (route6.hasNext()) {
                    while (route6.hasNext()) {
                        val prefix = route6.next()
                        builder.addRoute(prefix.address(), prefix.prefix())
                    }
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val exclude4 = options.inet4RouteExcludeAddress
                    while (exclude4.hasNext()) {
                        val prefix = exclude4.next()
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(prefix.address()), prefix.prefix()))
                    }
                    val exclude6 = options.inet6RouteExcludeAddress
                    while (exclude6.hasNext()) {
                        val prefix = exclude6.next()
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(prefix.address()), prefix.prefix()))
                    }
                }

                val includePackages = options.includePackage
                while (includePackages.hasNext()) builder.addAllowedApplication(includePackages.next())
                val excludePackages = options.excludePackage
                while (excludePackages.hasNext()) builder.addDisallowedApplication(excludePackages.next())
            }

            val descriptor = builder.establish()
                ?: error("Android refused to establish the VPN interface.")
            tunDescriptor?.close()
            tunDescriptor = descriptor
            tunOpened.set(true)
            return descriptor.fd
        }

        private fun startMonitor(listener: InterfaceUpdateListener) {
            closeMonitor()
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = updateDefaultInterface(listener, network)
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    updateDefaultInterface(listener, network)
                override fun onLost(network: Network) = listener.updateDefaultInterface("", -1, false, false)
            }
            callback = networkCallback
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            when {
                Build.VERSION.SDK_INT >= 31 ->
                    connectivity.registerBestMatchingNetworkCallback(request, networkCallback, Handler(Looper.getMainLooper()))
                Build.VERSION.SDK_INT >= 28 ->
                    connectivity.requestNetwork(request, networkCallback)
                else -> connectivity.registerDefaultNetworkCallback(networkCallback)
            }
        }

        private fun updateDefaultInterface(listener: InterfaceUpdateListener, network: Network) {
            val link = connectivity.getLinkProperties(network) ?: return
            val iface = runCatching { JavaNetworkInterface.getByName(link.interfaceName) }.getOrNull() ?: return
            val caps = connectivity.getNetworkCapabilities(network)
            val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            listener.updateDefaultInterface(link.interfaceName, iface.index, expensive, false)
        }

        fun closeMonitor() {
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            callback = null
        }

        private fun getInterfaces(): NetworkInterfaceIterator {
            val result = mutableListOf<LibboxNetworkInterface>()
            connectivity.allNetworks.forEach { network ->
                val link = connectivity.getLinkProperties(network) ?: return@forEach
                val caps = connectivity.getNetworkCapabilities(network) ?: return@forEach
                val iface = runCatching { JavaNetworkInterface.getByName(link.interfaceName) }.getOrNull()
                    ?: return@forEach
                val addresses = iface.interfaceAddresses.mapNotNull { entry ->
                    entry.address?.hostAddress?.let { it + "/" + entry.networkPrefixLength }
                }
                val gateways = link.routes
                    .filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway?.takeUnless { gateway -> gateway.isAnyLocalAddress }?.hostAddress }

                val item = LibboxNetworkInterface().apply {
                    name = iface.name
                    index = iface.index
                    mtu = runCatching { iface.mtu }.getOrDefault(1500)
                    this.addresses = StringArray(addresses)
                    dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress })
                    
                    gateway = StringArray(gateways)
                    type = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                    flags = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                    } else {
                        0
                    }
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
                result.add(item)
            }
            return InterfaceArray(result)
        }
    }

    private class StringArray(values: List<String>) : StringIterator {
        private val data = values.toList()
        private var index = 0
        override fun len(): Int = data.size
        override fun hasNext(): Boolean = index < data.size
        override fun next(): String = data[index++]
    }

    private class InterfaceArray(values: List<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        private val data = values.toList()
        private var index = 0
        override fun hasNext(): Boolean = index < data.size
        override fun next(): LibboxNetworkInterface = data[index++]
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
