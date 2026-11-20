package com.example.onetapvpn

import android.app.Application
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
                    Tunnel.State.UP -> current.copy(
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
                    backend.setState(tunnel, Tunnel.State.UP, config)
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

    fun onVpnPermissionDenied() {
        _uiState.update {
            it.copy(
                status = VpnStatus.DISCONNECTED,
                message = getApplication<Application>().getString(R.string.vpn_permission_denied),
                isBusy = false
            )
        }
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
    }
}
