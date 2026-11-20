package com.example.onetapvpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

internal class IranVpnManager(private val context: Context) {
    fun start(profile: IranVpnProfile, selectedPackages: Set<String>): Boolean = runCatching {
        val intent = Intent(context, IranVpnService::class.java).apply {
            putExtra(EXTRA_CONFIG, profile.configJson)
            putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(selectedPackages))
        }
        ContextCompat.startForegroundService(context, intent)
    }.isSuccess

    fun stop(): Boolean = runCatching {
        context.stopService(Intent(context, IranVpnService::class.java))
    }.getOrDefault(false)

    private companion object {
        const val EXTRA_CONFIG = "config_json"
        const val EXTRA_PACKAGES = "selected_packages"
    }
}
