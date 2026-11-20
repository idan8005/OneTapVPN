package com.example.onetapvpn

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class VpnStatus {
    SCANNING,
    READY,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

internal data class VpnUiState(
    val status: VpnStatus = VpnStatus.SCANNING,
    val message: String = "",
    val servers: List<VpnServer> = emptyList(),
    val currentServer: VpnServer? = null,
    val isRefreshing: Boolean = false,
    val routing: RoutingSettings = RoutingSettings(),
    val isBusy: Boolean = false
)

internal class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val api = VpnGateApi()
    private val vpnManager = VpnManager(application.applicationContext)
    private val routingPreferences = RoutingPreferences(application.applicationContext)

    private val mutableState = MutableStateFlow(
        VpnUiState(
            message = application.getString(R.string.status_scanning),
            routing = routingPreferences.read()
        )
    )
    val uiState: StateFlow<VpnUiState> = mutableState.asStateFlow()

    private var candidateServers: List<VpnServer> = emptyList()
    private var nextCandidateIndex = 0
    private var connectionRequested = false
    private var userRequestedDisconnect = false
    private var tunnelStarted = false
    private var switchingServer = false
    private var attemptSawProgress = false
    private var generation = 0
    private var watchdogJob: Job? = null
    private var switchJob: Job? = null
    private var ignoreTeardownUntil = 0L

    init {
        refreshServers()
    }

    fun refreshServers() {
        val current = mutableState.value
        if (current.isRefreshing ||
            current.status == VpnStatus.CONNECTING ||
            current.status == VpnStatus.CONNECTED
        ) return

        mutableState.update {
            it.copy(
                isRefreshing = true,
                status = if (it.servers.isEmpty()) VpnStatus.SCANNING else it.status,
                message = text(R.string.server_refreshing)
            )
        }

        viewModelScope.launch {
            try {
                val fetched = withContext(Dispatchers.IO) { api.fetchServers() }
                candidateServers = fetched.take(MAX_AUTO_CANDIDATES)
                mutableState.update { state ->
                    state.copy(
                        status = if (state.status == VpnStatus.CONNECTED) VpnStatus.CONNECTED else VpnStatus.READY,
                        message = text(R.string.server_ready_count, fetched.size),
                        servers = fetched,
                        currentServer = if (state.status == VpnStatus.CONNECTED) {
                            state.currentServer
                        } else {
                            fetched.firstOrNull()
                        },
                        isRefreshing = false
                    )
                }
                if (connectionRequested && mutableState.value.status != VpnStatus.CONNECTED) {
                    beginConnection()
                }
            } catch (error: Exception) {
                val description = error.localizedMessage
                    ?.replace(Regex("[\\r\\n\\t]+"), " ")
                    ?.take(180)
                    ?.takeIf { it.isNotBlank() }
                    ?: text(R.string.status_error)
                mutableState.update {
                    it.copy(
                        status = if (it.servers.isEmpty()) VpnStatus.ERROR else it.status,
                        message = text(R.string.server_load_failed, description),
                        isRefreshing = false
                    )
                }
                connectionRequested = false
            }
        }
    }

    fun setRouting(settings: RoutingSettings) {
        val current = mutableState.value
        if (current.status == VpnStatus.CONNECTED || current.status == VpnStatus.CONNECTING) {
            mutableState.update {
                it.copy(message = text(R.string.change_routing_disconnect_first))
            }
            return
        }

        routingPreferences.write(settings)
        mutableState.update {
            it.copy(
                routing = settings,
                message = when (settings.mode) {
                    RoutingMode.FULL_TUNNEL -> text(R.string.full_tunnel_description)
                    RoutingMode.SELECTED_APPS -> text(
                        R.string.choose_apps_count,
                        settings.selectedPackages.size
                    )
                }
            )
        }
    }

    fun connect() {
        val state = mutableState.value
        if (state.status == VpnStatus.CONNECTING || state.status == VpnStatus.CONNECTED) return

        if (state.routing.mode == RoutingMode.SELECTED_APPS &&
            state.routing.selectedPackages.isEmpty()
        ) {
            mutableState.update {
                it.copy(status = VpnStatus.READY, message = text(R.string.choose_app_before_connect))
            }
            return
        }

        userRequestedDisconnect = false
        connectionRequested = true

        if (state.servers.isEmpty()) {
            mutableState.update {
                it.copy(status = VpnStatus.SCANNING, message = text(R.string.status_scanning))
            }
            refreshServers()
            return
        }
        beginConnection()
    }

    private fun beginConnection() {
        if (!connectionRequested || candidateServers.isEmpty()) return
        generation++
        nextCandidateIndex = 0
        startNextServer(text(R.string.reason_first_server), generation)
    }

    private fun startNextServer(reason: String, expectedGeneration: Int = generation) {
        if (!connectionRequested || userRequestedDisconnect || expectedGeneration != generation) return

        watchdogJob?.cancel()
        switchJob?.cancel()

        if (nextCandidateIndex >= candidateServers.size) {
            connectionRequested = false
            tunnelStarted = false
            switchingServer = false
            vpnManager.stop()
            mutableState.update {
                it.copy(
                    status = VpnStatus.ERROR,
                    isBusy = false,
                    message = text(R.string.no_server_connected)
                )
            }
            return
        }

        val server = candidateServers[nextCandidateIndex++]
        val attemptNumber = nextCandidateIndex
        attemptSawProgress = false
        switchingServer = tunnelStarted

        mutableState.update {
            it.copy(
                status = VpnStatus.CONNECTING,
                currentServer = server,
                isBusy = true,
                message = text(
                    R.string.server_attempt_format,
                    reason,
                    server.country,
                    server.pingMs,
                    attemptNumber,
                    candidateServers.size
                )
            )
        }

        switchJob = viewModelScope.launch {
            if (tunnelStarted) {
                vpnManager.stop()
                ignoreTeardownUntil = SystemClock.elapsedRealtime() + TEARDOWN_GRACE_MS
                delay(SWITCH_DELAY_MS)
            }
            if (!connectionRequested || userRequestedDisconnect || expectedGeneration != generation) return@launch

            switchingServer = false
            val launched = vpnManager.start(server, mutableState.value.routing)
            if (!launched) {
                startNextServer(text(R.string.reason_server_invalid), expectedGeneration)
                return@launch
            }

            tunnelStarted = true
            watchdogJob = viewModelScope.launch {
                delay(CONNECT_TIMEOUT_MS)
                if (connectionRequested &&
                    mutableState.value.status == VpnStatus.CONNECTING &&
                    expectedGeneration == generation
                ) {
                    startNextServer(text(R.string.reason_server_timeout), expectedGeneration)
                }
            }
        }
    }

    fun onVpnState(state: String?, message: String?, level: ConnectionStatus?) {
        when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                if (userRequestedDisconnect || !connectionRequested) return
                watchdogJob?.cancel()
                switchingServer = false
                mutableState.update {
                    it.copy(
                        status = VpnStatus.CONNECTED,
                        isBusy = false,
                        message = text(
                            R.string.connected_to_country,
                            it.currentServer?.country ?: text(R.string.app_name)
                        )
                    )
                }
            }

            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                if (connectionRequested && !switchingServer &&
                    mutableState.value.status == VpnStatus.CONNECTING
                ) {
                    startNextServer(text(R.string.reason_server_rejected))
                }
            }

            ConnectionStatus.LEVEL_START,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT,
            ConnectionStatus.LEVEL_NONETWORK -> {
                if (mutableState.value.status == VpnStatus.CONNECTING) {
                    attemptSawProgress = true
                }
            }

            ConnectionStatus.LEVEL_NOTCONNECTED -> {
                if (switchingServer || SystemClock.elapsedRealtime() < ignoreTeardownUntil) return
                when {
                    userRequestedDisconnect -> Unit
                    mutableState.value.status == VpnStatus.CONNECTED -> {
                        connectionRequested = true
                        startNextServer(text(R.string.reason_server_dropped))
                    }
                    connectionRequested && attemptSawProgress -> {
                        startNextServer(text(R.string.reason_server_dropped))
                    }
                }
            }

            else -> {
                // Other OpenVPN status levels are informational.
            }
        }
    }

    fun disconnect() {
        generation++
        connectionRequested = false
        userRequestedDisconnect = true
        watchdogJob?.cancel()
        switchJob?.cancel()
        switchingServer = false
        vpnManager.stop()
        tunnelStarted = false
        mutableState.update {
            it.copy(
                status = VpnStatus.DISCONNECTED,
                isBusy = false,
                message = text(R.string.vpn_disconnected)
            )
        }
    }

    fun onVpnPermissionDenied() {
        generation++
        connectionRequested = false
        userRequestedDisconnect = true
        watchdogJob?.cancel()
        switchJob?.cancel()
        mutableState.update {
            it.copy(
                status = VpnStatus.DISCONNECTED,
                isBusy = false,
                message = text(R.string.vpn_permission_denied)
            )
        }
    }

    private fun text(resourceId: Int, vararg arguments: Any): String =
        getApplication<Application>().getString(resourceId, *arguments)

    override fun onCleared() {
        watchdogJob?.cancel()
        switchJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_AUTO_CANDIDATES = 30
        const val CONNECT_TIMEOUT_MS = 18_000L
        const val SWITCH_DELAY_MS = 500L
        const val TEARDOWN_GRACE_MS = 1_800L
    }
}
