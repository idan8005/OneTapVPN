
package com.example.onetapvpn

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF07111F)
private val Panel = Color(0xFF101F32)
private val Mint = Color(0xFF32D6A0)
private val Muted = Color(0xFF9AADC3)

@Composable
internal fun RaahatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Mint,
            onPrimary = Color(0xFF062017),
            background = Night,
            surface = Panel,
            onSurface = Color(0xFFF4F8FC),
            secondary = Color(0xFF8CB9FF)
        ),
        content = content
    )
}

@Composable
internal fun RaahatScreen(
    state: VpnUiState,
    onConnect: () -> Unit,
    onImportFile: () -> Unit,
    onPaste: () -> Unit,
    onExport: () -> Unit
) {
    var advanced by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    val connected = state.status == VpnStatus.CONNECTED
    val busy = state.isBusy
    val pulse by animateFloatAsState(if (busy) 0.96f else 1f, label = "connection-pulse")

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(R.string.confirm_export_title)) },
            text = { Text(stringResource(R.string.confirm_export)) },
            confirmButton = {
                TextButton(onClick = { confirmExport = false; onExport() }) {
                    Text(stringResource(R.string.continue_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExport = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0C1B2D), Night, Color(0xFF050B13)))
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(17.dp))
                        .background(Brush.linearGradient(listOf(Mint, Color(0xFF138E86)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("R", fontSize = 27.sp, fontWeight = FontWeight.Black, color = Night)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.app_tagline), color = Muted, fontSize = 12.sp)
                }
                Text("●", color = if (connected) Mint else Muted, fontSize = 12.sp)
            }

            Spacer(Modifier.height(34.dp))
            Text(stringResource(R.string.hero_title), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.connection_title), color = Muted, fontSize = 12.sp,
                letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))

            Box(
                Modifier.size(238.dp).scale(pulse).clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                if (connected) Color(0xFF164E4A) else Color(0xFF173B4A),
                                Color(0xFF10263A),
                                Color(0xFF0B1929)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(196.dp).clip(CircleShape)
                        .background(Color(0xFF172D42)),
                    contentAlignment = Alignment.Center
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(82.dp),
                            color = Mint,
                            strokeWidth = 5.dp
                        )
                    } else {
                        Text(if (connected) "✓" else "⏻",
                            color = Mint, fontSize = 62.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            AnimatedContent(targetState = state.status, label = "vpn-status") { status ->
                val text = when (status) {
                    VpnStatus.CONNECTED -> stringResource(R.string.status_connected)
                    VpnStatus.CONNECTING -> stringResource(R.string.status_connecting)
                    VpnStatus.DISCONNECTING -> stringResource(R.string.status_disconnecting)
                    VpnStatus.ERROR -> stringResource(R.string.status_error)
                    VpnStatus.DISCONNECTED -> stringResource(R.string.status_disconnected)
                }
                Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = if (connected) Mint else Color.White, textAlign = TextAlign.Center)
            }
            if (state.message.isNotBlank() && state.status == VpnStatus.ERROR) {
                Spacer(Modifier.height(8.dp))
                Text(state.message, color = Color(0xFFFFA5A5), fontSize = 12.sp,
                    textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onConnect,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connected) Color(0xFFB64B55) else Mint,
                    contentColor = if (connected) Color.White else Color(0xFF062017),
                    disabledContainerColor = Color(0xFF294052)
                )
            ) {
                Text(
                    stringResource(if (connected) R.string.disconnect else if (busy) R.string.connecting_button else R.string.connect),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.tap_to_connect), color = Muted, fontSize = 12.sp,
                textAlign = TextAlign.Center, lineHeight = 19.sp)

            Spacer(Modifier.height(24.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC12243A)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    InfoLine(stringResource(R.string.protocol_label), stringResource(R.string.protocol_value))
                    HorizontalDivider(color = Color(0xFF263B52))
                    InfoLine(stringResource(R.string.privacy_label), stringResource(R.string.privacy_value))
                    HorizontalDivider(color = Color(0xFF263B52))
                    InfoLine(stringResource(R.string.quick_setup_label), stringResource(R.string.quick_setup_value))
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = { advanced = !advanced },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(if (advanced) R.string.hide_advanced_settings else R.string.advanced_settings))
            }
            if (advanced) {
                Spacer(Modifier.height(12.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.profile_section), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(stringResource(R.string.advanced_settings_description), color = Muted, fontSize = 13.sp)
                        Button(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.import_file))
                        }
                        OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.paste_clipboard))
                        }
                        OutlinedButton(onClick = { confirmExport = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.export_profile))
                        }
                        Text(stringResource(R.string.export_profile_warning), color = Color(0xFFFFC78A), fontSize = 11.sp)
                        HorizontalDivider(color = Color(0xFF263B52))
                        Text(stringResource(R.string.warp_note), color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.version_label), color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun InfoLine(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Muted, fontSize = 13.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
