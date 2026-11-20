package com.example.onetapvpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    fun saveDiscovery(autoProfile: IranVpnProfile, servers: List<IranVpnProfile>) {
        val serverArray = JSONArray()
        servers.forEach { server ->
            serverArray.put(
                JSONObject()
                    .put("name", server.name)
                    .put("host", server.serverHost)
                    .put("port", server.serverPort)
                    .put("config", server.configJson)
                    .put("candidates", server.candidateCount)
                    .put("sources", server.sourceCount)
            )
        }
        val editor = preferences.edit()
            .putString(KEY_SERVERS, serverArray.toString())
        editor.putString(KEY_NAME, autoProfile.name)
            .putString(KEY_HOST, autoProfile.serverHost)
            .putInt(KEY_PORT, autoProfile.serverPort)
            .putString(KEY_CONFIG, autoProfile.configJson)
            .putInt(KEY_CANDIDATES, autoProfile.candidateCount)
            .putInt(KEY_SOURCES, autoProfile.sourceCount)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
        check(editor.commit()) { "Could not save the discovered VPN servers." }
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

    fun readServers(): List<IranVpnProfile> {
        val raw = preferences.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val config = item.optString("config").takeIf { it.isNotBlank() } ?: continue
                    val host = item.optString("host").takeIf { it.isNotBlank() } ?: continue
                    add(
                        IranVpnProfile(
                            name = item.optString("name", host),
                            serverHost = host,
                            serverPort = item.optInt("port"),
                            configJson = config,
                            candidateCount = item.optInt("candidates", 1),
                            sourceCount = item.optInt("sources", 1)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveSelection(serverKey: String) {
        preferences.edit().putString(KEY_SELECTED_SERVER, serverKey).apply()
    }

    fun readSelectionKey(): String =
        preferences.getString(KEY_SELECTED_SERVER, AUTO_SELECTION_KEY) ?: AUTO_SELECTION_KEY

    fun selectedProfile(): IranVpnProfile? {
        if (readSelectionKey() == AUTO_SELECTION_KEY) return read()
        return readServers().firstOrNull { it.selectionKey == readSelectionKey() } ?: read()
    }

    fun isStale(maxAgeMillis: Long = DEFAULT_MAX_AGE_MS): Boolean {
        val savedAt = preferences.getLong(KEY_SAVED_AT, 0L)
        return savedAt <= 0L || System.currentTimeMillis() - savedAt >= maxAgeMillis
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    internal companion object {
        const val AUTO_SELECTION_KEY = "__auto_best_server__"
        private const val PREFERENCES_NAME = "raahat_vpn_iran_profile"
        private const val KEY_NAME = "name"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_CONFIG = "config"
        private const val KEY_CANDIDATES = "candidates"
        private const val KEY_SOURCES = "sources"
        private const val KEY_SAVED_AT = "saved_at"
        private const val KEY_SERVERS = "servers"
        private const val KEY_SELECTED_SERVER = "selected_server"
        private const val DEFAULT_MAX_AGE_MS = 20 * 60 * 1000L
    }
}

internal val IranVpnProfile.selectionKey: String
    get() = "${serverHost.lowercase()}:$serverPort"
