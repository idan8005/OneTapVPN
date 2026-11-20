package com.example.onetapvpn

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

internal data class VpnUiState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val message: String = "",
    val isBusy: Boolean = false
)

/** Keeps one WireGuard backend and serializes all backend/network operations on a worker thread. */
internal class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = SecureConfigStore(application)
    private val backend: Backend = GoBackend(application.applicationContext)
    private val selectionPrefs =
        application.getSharedPreferences(SELECTION_PREFS, Context.MODE_PRIVATE)
    private val workerDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RaahatVPN-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val _uiState = MutableStateFlow(
        VpnUiState(message = application.getString(R.string.status_disconnected))
    )
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            _uiState.update { current ->
                when (newState) {
                    Tunnel.State.UP ->
                        // While connecting, the connect flow reports the final state itself; the
                        // tunnel may go up and down several times while the fastest server is chosen.
                        if (current.status == VpnStatus.CONNECTING) current
                        else current.copy(
                            status = VpnStatus.CONNECTED,
                            message = getApplication<Application>().getString(R.string.status_connected),
                            isBusy = false
                        )
                    Tunnel.State.DOWN -> {
                        if (current.status == VpnStatus.CONNECTING) current
                        else current.copy(
                            status = VpnStatus.DISCONNECTED,
                            message = getApplication<Application>().getString(R.string.status_disconnected),
                            isBusy = false
                        )
                    }
                    else -> current
                }
            }
        }
    }

    fun refreshState() {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            try {
                val isConnected = withContext(workerDispatcher) {
                    backend.getState(tunnel) == Tunnel.State.UP
                }
                _uiState.update { current ->
                    if (current.isBusy) current
                    else current.copy(
                        status = if (isConnected) VpnStatus.CONNECTED else VpnStatus.DISCONNECTED,
                        message = getApplication<Application>().getString(
                            if (isConnected) R.string.status_connected else R.string.status_disconnected
                        )
                    )
                }
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    fun connect() {
        if (_uiState.value.isBusy || _uiState.value.status == VpnStatus.CONNECTED) return
        _uiState.update {
            it.copy(
                status = VpnStatus.CONNECTING,
                message = getApplication<Application>().getString(R.string.status_connecting),
                isBusy = true
            )
        }

        viewModelScope.launch {
            try {
                val finalState = withContext(workerDispatcher) {
                    val savedProfile = configStore.read()
                    val profile = savedProfile ?: WarpProvisioner.provisionWithRetry()
                    val config = WireGuardProfileValidator.parse(profile)
                    if (savedProfile == null) configStore.write(profile)
                    if (ServerSelector.isWarpProfile(profile)) connectToFastestServer(profile)
                    else backend.setState(tunnel, Tunnel.State.UP, config)
                }
                updateFromBackend(finalState)
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    fun disconnect() {
        if (_uiState.value.isBusy || _uiState.value.status != VpnStatus.CONNECTED) return
        _uiState.update {
            it.copy(
                status = VpnStatus.DISCONNECTING,
                message = getApplication<Application>().getString(R.string.status_disconnecting),
                isBusy = true
            )
        }

        viewModelScope.launch {
            try {
                // A null Config is intentional for DOWN; disconnect must not fetch or parse a profile.
                val finalState = withContext(workerDispatcher) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                }
                updateFromBackend(finalState)
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    fun importProfile(profileText: String) {
        if (_uiState.value.isBusy || _uiState.value.status == VpnStatus.CONNECTED) {
            _uiState.update {
                it.copy(message = getApplication<Application>().getString(R.string.disconnect_before_import))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, message = getApplication<Application>().getString(R.string.validating_profile))
            }
            try {
                withContext(workerDispatcher) {
                    WireGuardProfileValidator.parse(profileText)
                    configStore.write(profileText.trim())
                }
                _uiState.update {
                    it.copy(
                        status = VpnStatus.DISCONNECTED,
                        message = getApplication<Application>().getString(R.string.profile_imported),
                        isBusy = false
                    )
                }
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    suspend fun readProfileForExport(): String? = withContext(workerDispatcher) {
        configStore.read()
    }

    fun reportExternalMessage(message: String) {\n        _uiState.update { it.copy(message = message, isBusy = false) }\n    }\n\n    fun reportExternalError(message: String) {\n        _uiState.update { it.copy(status = VpnStatus.ERROR, message = message, isBusy = false) }\n    }\n\n    fun onVpnPermissionDenied() {
        _uiState.update {
            it.copy(
                status = VpnStatus.DISCONNECTED,
                message = getApplication<Application>().getString(R.string.vpn_permission_denied),
                isBusy = false
            )
        }
    }

    /**
     * Tries WARP endpoints one after another, measures how long each takes to complete a WireGuard
     * handshake, and leaves the tunnel up on the quickest one. The winner is remembered and tried
     * first next time, so later connections usually stop after a single quick check.
     * Must run on the worker thread.
     */
    private fun connectToFastestServer(profile: String): Tunnel.State {
        _uiState.update {
            it.copy(message = getApplication<Application>().getString(R.string.status_finding_server))
        }
        val budgetEnd = SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS
        val preferred = selectionPrefs.getString(KEY_BEST_ENDPOINT, null)
        var best: String? = null
        var bestMillis = Long.MAX_VALUE
        var upOn: String? = null

        for (endpoint in ServerSelector.orderedCandidates(profile, preferred)) {
            if (SystemClock.elapsedRealtime() >= budgetEnd) break
            val millis = probeHandshake(profile, endpoint) ?: continue
            if (millis < bestMillis) {
                best = endpoint
                bestMillis = millis
            }
            if (endpoint == preferred && millis <= EARLY_STOP_MS) {
                upOn = endpoint
                break
            }
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        }

        best?.let { selectionPrefs.edit().putString(KEY_BEST_ENDPOINT, it).apply() }
        if (upOn != null) return Tunnel.State.UP

        // No server answered in time: fall back to the profile as saved, as before.
        val finalProfile = best?.let { ServerSelector.withEndpoint(profile, it) } ?: profile
        return backend.setState(tunnel, Tunnel.State.UP, WireGuardProfileValidator.parse(finalProfile))
    }

    /** Brings the tunnel up on [endpoint]; returns millis to the first handshake, or null (tunnel left down). */
    private fun probeHandshake(profile: String, endpoint: String): Long? {
        val config = try {
            WireGuardProfileValidator.parse(ServerSelector.withEndpoint(profile, endpoint))
        } catch (error: Exception) {
            return null
        }
        val start = SystemClock.elapsedRealtime()
        try {
            backend.setState(tunnel, Tunnel.State.UP, config)
            while (SystemClock.elapsedRealtime() - start < PROBE_TIMEOUT_MS) {
                if (hasHandshake()) return SystemClock.elapsedRealtime() - start
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            // Treat as an unreachable server and try the next one.
        }
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (error: Exception) {
            // Already down.
        }
        return null
    }

    private fun hasHandshake(): Boolean {
        val stats = backend.getStatistics(tunnel)
        return stats.peers().any { key -> (stats.peer(key)?.latestHandshakeEpochMillis() ?: 0L) > 0L }
    }

    private fun updateFromBackend(state: Tunnel.State) {
        val connected = state == Tunnel.State.UP
        _uiState.update {
            it.copy(
                status = if (connected) VpnStatus.CONNECTED else VpnStatus.DISCONNECTED,
                message = getApplication<Application>().getString(
                    if (connected) R.string.status_connected else R.string.status_disconnected
                ),
                isBusy = false
            )
        }
    }

    private fun showError(error: Exception) {
        val detail = error.localizedMessage
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.take(MAX_ERROR_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?: getApplication<Application>().getString(R.string.unknown_error)

        _uiState.update {
            it.copy(
                status = VpnStatus.ERROR,
                message = getApplication<Application>().getString(R.string.error_prefix, detail),
                isBusy = false
            )
        }
    }

    override fun onCleared() {
        workerDispatcher.close()
        super.onCleared()
    }

    private companion object {
        const val TUNNEL_NAME = "onetap"
        const val MAX_ERROR_CHARS = 240
        const val SELECTION_PREFS = "onetapvpn_server_selection"
        const val KEY_BEST_ENDPOINT = "best_endpoint"
        const val PROBE_TIMEOUT_MS = 2_500L
        const val POLL_INTERVAL_MS = 100L
        const val TOTAL_BUDGET_MS = 15_000L
        const val EARLY_STOP_MS = 1_500L
    }
}
