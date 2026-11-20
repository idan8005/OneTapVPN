package com.example.onetapvpn

import android.content.Context

internal class IranVpnProfileStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(profile: IranVpnProfile) {
        check(
            preferences.edit()
                .putString(KEY_NAME, profile.name)
                .putString(KEY_HOST, profile.serverHost)
                .putInt(KEY_PORT, profile.serverPort)
                .putString(KEY_CONFIG, profile.configJson)
                .commit()
        ) { "Could not save the Iran VPN profile." }
    }

    fun read(): IranVpnProfile? {
        val config = preferences.getString(KEY_CONFIG, null)?.takeIf { it.isNotBlank() } ?: return null
        val host = preferences.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = preferences.getString(KEY_NAME, host).orEmpty()
        val port = preferences.getInt(KEY_PORT, 443)
        return IranVpnProfile(name, host, port, config)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "raahat_vpn_iran_profile"
        const val KEY_NAME = "name"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_CONFIG = "config"
    }
}
