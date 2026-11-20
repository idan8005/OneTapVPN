
package com.example.onetapvpn

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: VpnViewModel
    private var pendingExportProfile: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.connect()
        else viewModel.onVpnPermissionDenied()
    }

    private val profilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val content = StringBuilder()
                        val buffer = CharArray(4096)
                        while (content.length <= WireGuardProfileValidator.MAX_PROFILE_CHARS) {
                            val remaining = WireGuardProfileValidator.MAX_PROFILE_CHARS + 1 - content.length
                            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count < 0) break
                            content.append(buffer, 0, count)
                        }
                        require(content.length <= WireGuardProfileValidator.MAX_PROFILE_CHARS) {
                            getString(R.string.profile_too_large)
                        }
                        content.toString()
                    } ?: error(getString(R.string.cannot_read_profile))
                }
                viewModel.importProfile(profile)
            } catch (error: Exception) {
                viewModel.reportExternalError(error.localizedMessage ?: getString(R.string.unknown_error))
            }
        }
    }

    private val profileSaver = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val profile = pendingExportProfile
        pendingExportProfile = null
        if (uri != null && profile != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)
                        ?.use { it.write(profile) } ?: error(getString(R.string.profile_export_failed))
                }
                viewModel.reportExternalMessage(getString(R.string.profile_exported))
            } catch (error: Exception) {
                viewModel.reportExternalError(error.localizedMessage ?: getString(R.string.unknown_error))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[VpnViewModel::class.java]
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            RaahatTheme {
                RaahatScreen(
                    state = state,
                    onConnect = ::requestConnection,
                    onImportFile = { profilePicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
                    onPaste = ::importFromClipboard,
                    onExport = ::exportProfile
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
    }

    private fun requestConnection() {
        if (viewModel.uiState.value.status == VpnStatus.CONNECTED) {
            viewModel.disconnect()
            return
        }
        if (viewModel.uiState.value.isBusy) return
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) viewModel.connect()
        else vpnPermissionLauncher.launch(permissionIntent)
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) viewModel.reportExternalError(getString(R.string.clipboard_empty))
        else viewModel.importProfile(text)
    }

    private fun exportProfile() {
        lifecycleScope.launch {
            try {
                val profile = viewModel.readProfileForExport()
                if (profile.isNullOrBlank()) {
                    viewModel.reportExternalError(getString(R.string.no_profile_to_export))
                    return@launch
                }
                pendingExportProfile = profile
                profileSaver.launch("RaahatVPN-profile.conf")
            } catch (error: Exception) {
                viewModel.reportExternalError(error.localizedMessage ?: getString(R.string.unknown_error))
            }
        }
    }
}
