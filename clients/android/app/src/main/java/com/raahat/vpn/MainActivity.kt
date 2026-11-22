package com.raahat.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var currentState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var currentMessage by mutableStateOf("برای اتصال، لینک یک سرور VLESS + REALITY را وارد کنید.")
    private var pendingConfig: String? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                pendingConfig?.let { RaahatVpnService.start(this, it) }
            } else {
                currentState = ConnectionState.DISCONNECTED
                currentMessage = "مجوز اتصال VPN داده نشد."
            }
            pendingConfig = null
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(RaahatVpnService.EXTRA_STATE)
            currentState = runCatching { ConnectionState.valueOf(state.orEmpty()) }
                .getOrDefault(ConnectionState.DISCONNECTED)
            currentMessage = intent.getStringExtra(RaahatVpnService.EXTRA_MESSAGE).orEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readSavedState()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    RaahatHome(
                        state = currentState,
                        message = currentMessage,
                        onConnect = ::requestConnection,
                        onDisconnect = { RaahatVpnService.stop(this) }
                    )
                }
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
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onStop()
    }

    private fun readSavedState() {
        val preferences = getSharedPreferences("raahat_connection", Context.MODE_PRIVATE)
        currentState = runCatching {
            ConnectionState.valueOf(preferences.getString("state", ConnectionState.DISCONNECTED.name).orEmpty())
        }.getOrDefault(ConnectionState.DISCONNECTED)
        currentMessage = preferences.getString("message", "").orEmpty()
    }

    private fun requestConnection(link: String): Boolean {
        return try {
            val profile = VlessRealityProfile.parse(link)
            pendingConfig = profile.toSingBoxConfig()
            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent == null) {
                pendingConfig?.let { RaahatVpnService.start(this, it) }
                pendingConfig = null
            } else {
                vpnPermissionLauncher.launch(permissionIntent)
            }
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
}

@Composable
private fun RaahatHome(
    state: ConnectionState,
    message: String,
    onConnect: (String) -> Boolean,
    onDisconnect: () -> Unit
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var serverLink by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_raahat_vpn),
                contentDescription = "Raahat VPN",
                modifier = Modifier.size(112.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("راحت وی‌پی‌ان", style = MaterialTheme.typography.headlineMedium)
            Text("اتصال امن و ساده", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(28.dp))
            Card {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
                    Spacer(Modifier.height(8.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(20.dp))
            when (state) {
                ConnectionState.CONNECTED,
                ConnectionState.CONNECTING,
                ConnectionState.DISCONNECTING -> Button(
                    onClick = onDisconnect,
                    enabled = state != ConnectionState.DISCONNECTING
                ) { Text("قطع اتصال") }
                else -> Button(onClick = { showImportDialog = true }) { Text("اتصال به سرور") }
            }
            Spacer(Modifier.height(10.dp))
            Text("نسخه 0.2.0-dev", style = MaterialTheme.typography.labelSmall)
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
                    if (onConnect(serverLink)) {
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
