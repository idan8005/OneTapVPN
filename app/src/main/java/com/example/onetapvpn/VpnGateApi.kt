package com.example.onetapvpn

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class VpnGateApi {
    @Throws(IOException::class)
    fun fetchServers(): List<VpnServer> {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        val payload = try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("VPN Gate returned HTTP $responseCode.")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }

        if (payload.isBlank()) throw IOException("VPN Gate returned an empty server list.")

        val servers = payload.lineSequence()
            .mapNotNull(::parseServer)
            .distinctBy { it.ip }
            .sortedWith(
                compareByDescending<VpnServer> { ServerRanker.score(it) }
                    .thenBy { it.pingMs }
                    .thenByDescending { it.speedBytesPerSecond }
            )
        if (servers.isEmpty()) throw IOException("No usable OpenVPN servers were returned.")
        return servers
    }

    private fun parseServer(line: String): VpnServer? {
        if (line.isBlank()) return null
        val fields = parseCsvLine(line)
        if (fields.size < MIN_FIELDS) return null
        if (fields.firstOrNull().orEmpty().startsWith("*") ||
            fields.firstOrNull().orEmpty().startsWith("#")
        ) return null

        val ip = fields.getOrNull(1)?.trim().orEmpty()
        if (!IPV4_PATTERN.matches(ip)) return null

        val ping = fields.getOrNull(3)?.trim()?.toIntOrNull() ?: return null
        val speed = fields.getOrNull(4)?.trim()?.toLongOrNull() ?: return null
        val sessions = fields.getOrNull(7)?.trim()?.toLongOrNull() ?: 0L
        if (ping !in 1..MAX_PING_MS || speed <= 0L || sessions <= 0L) return null

        val encodedConfig = fields.last().trim()
        if (encodedConfig.length < MIN_CONFIG_LENGTH) return null
        val decoded = try {
            Base64.decode(encodedConfig, Base64.DEFAULT).toString(Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!decoded.contains("client") || !decoded.contains("remote ")) return null

        return VpnServer(
            hostName = fields[0].trim(),
            ip = ip,
            score = fields.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L,
            pingMs = ping,
            speedBytesPerSecond = speed,
            country = fields.getOrNull(5)?.trim().orEmpty().ifBlank { "Unknown" },
            countryCode = fields.getOrNull(6)?.trim().orEmpty(),
            activeSessions = sessions,
            uptimeMs = fields.getOrNull(8)?.trim()?.toLongOrNull() ?: 0L,
            operator = fields.getOrNull(12)?.trim().orEmpty(),
            configBase64 = encodedConfig
        )
    }

    /**
     * Parses the CSV quoting rules used by the public directory. The free-text
     * operator/message columns can contain commas, so a plain split(',') is unsafe.
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0

        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(character)
            }
            index++
        }

        fields.add(current.toString())
        return fields
    }

    private companion object {
        const val API_URL = "https://www.vpngate.net/api/iphone/"
        const val USER_AGENT = "RaahatVPN-Android/1.4.0"
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 20_000
        const val MIN_FIELDS = 15
        const val MIN_CONFIG_LENGTH = 150
        const val MAX_PING_MS = 600
        val IPV4_PATTERN = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
    }
}

internal object ServerRanker {
    /**
     * Ranks directory candidates using the published ping, throughput, active
     * sessions, and uptime. The VPN tunnel handshake remains the final check.
     */
    fun score(server: VpnServer): Double {
        val latencyPoints = 35.0 / (1.0 + server.pingMs / 90.0)
        val mbps = server.speedMbps.coerceAtLeast(0.0)
        val speedPoints = 35.0 * (mbps / (mbps + 45.0))
        val sessionsPoints = 15.0 * (server.activeSessions / (server.activeSessions + 15.0))
        val uptimeHours = (server.uptimeMs / 3_600_000.0).coerceAtLeast(0.0)
        val uptimePoints = 15.0 * (uptimeHours / (uptimeHours + 48.0))
        val sourceScorePoints = if (server.score > 0L) {
            (kotlin.math.ln1p(server.score.toDouble()) /
                kotlin.math.ln1p(1_000_000.0)).coerceIn(0.0, 1.0) * 5.0
        } else 0.0
        return latencyPoints + speedPoints + sessionsPoints + uptimePoints + sourceScorePoints
    }
}
