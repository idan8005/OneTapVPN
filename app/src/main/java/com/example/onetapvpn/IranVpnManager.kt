package com.example.onetapvpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

internal class IranVpnManager(private val context: Context) {
    fun start(
        profile: IranVpnProfile,
        selectedPackages: Set<String>,
        selectedOutboundTag: String
    ): Boolean = runCatching {
        val intent = Intent(context, IranVpnService::class.java).apply {
            putExtra(EXTRA_CONFIG, profile.configJson)
            putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(selectedPackages))
            putExtra(EXTRA_SELECTED_OUTBOUND, selectedOutboundTag)
        }
        ContextCompat.startForegroundService(context, intent)
    }.isSuccess

    fun stop(): Boolean = runCatching {
        // Ask the running VpnService to close sing-box and its TUN descriptor
        // before Android destroys the service. stopService() alone skipped
        // the explicit stop command and gave the UI no reliable stop handshake.
        context.startService(
            Intent(context, IranVpnService::class.java).setAction(ACTION_STOP)
        )
        true
    }.getOrElse {
        // Best-effort fallback if Android rejects the explicit stop command.
        context.stopService(Intent(context, IranVpnService::class.java))
    }

    private companion object {
        const val EXTRA_CONFIG = "config_json"
        const val EXTRA_PACKAGES = "selected_packages"
        const val EXTRA_SELECTED_OUTBOUND = "selected_outbound_tag"
        const val ACTION_STOP = "com.example.onetapvpn.iran.STOP"
    }
}
