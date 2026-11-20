package com.example.onetapvpn

import android.content.Context

internal enum class RoutingMode {
    FULL_TUNNEL,
    SELECTED_APPS
}

internal data class RoutingSettings(
    val mode: RoutingMode = RoutingMode.FULL_TUNNEL,
    val selectedPackages: Set<String> = emptySet()
)

internal class RoutingPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): RoutingSettings {
        val mode = runCatching {
            RoutingMode.valueOf(preferences.getString(KEY_MODE, RoutingMode.FULL_TUNNEL.name).orEmpty())
        }.getOrDefault(RoutingMode.FULL_TUNNEL)
        return RoutingSettings(
            mode = mode,
            selectedPackages = preferences.getStringSet(KEY_PACKAGES, emptySet())
                ?.toSet()
                .orEmpty()
        )
    }

    fun write(settings: RoutingSettings) {
        preferences.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putStringSet(KEY_PACKAGES, settings.selectedPackages.toSet())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "raahat_vpn_app_routing"
        const val KEY_MODE = "routing_mode"
        const val KEY_PACKAGES = "selected_packages"
    }
}
