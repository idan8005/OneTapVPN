package com.example.onetapvpn

import android.content.Context
import android.os.SystemClock

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
                .putInt(KEY_CANDIDATES, profile.candidateCount)
                .putInt(KEY_SOURCES, profile.sourceCount)
                .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                .commit()
        ) { "Could not save the Iran VPN profile." }
    }

    fun read(): IranVpnProfile? {
        val config = preferences.getString(KEY_CONFIG, null)?.takeIf { it.isNotBlank() } ?: return null
        val host = preferences.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = preferences.getString(KEY_NAME, host).orEmpty()
        val port = preferences.getInt(KEY_PORT, 0)
        val candidates = preferences.getInt(KEY_CANDIDATES, 1)
        val sources = preferences.getInt(KEY_SOURCES, 1)
        return IranVpnProfile(name, host, port, config, candidates, sources)
    }

    fun isStale(maxAgeMillis: Long = DEFAULT_MAX_AGE_MS): Boolean {
        val savedAt = preferences.getLong(KEY_SAVED_AT, 0L)
        return savedAt <= 0L || System.currentTimeMillis() - savedAt >= maxAgeMillis
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
        const val KEY_CANDIDATES = "candidates"
        const val KEY_SOURCES = "sources"
        const val KEY_SAVED_AT = "saved_at"
        const val DEFAULT_MAX_AGE_MS = 20 * 60 * 1000L
    }
}
