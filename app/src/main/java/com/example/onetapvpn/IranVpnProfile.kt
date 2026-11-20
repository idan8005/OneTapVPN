package com.example.onetapvpn

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class IranVpnProfile(
    val name: String,
    val serverHost: String,
    val serverPort: Int,
    val configJson: String
)

internal object IranVpnProfileParser {
    fun parse(link: String): IranVpnProfile {
        val value = link.trim()
        require(value.startsWith("vless://", ignoreCase = true)) {
            "Only VLESS links are supported in Iran mode."
        }

        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("The VLESS link is malformed.", error)
        }
        require(uri.scheme.equals("vless", ignoreCase = true)) { "Invalid VLESS scheme." }
        val uuid = decode(uri.rawUserInfo.orEmpty())
        require(runCatching { UUID.fromString(uuid) }.isSuccess) { "The VLESS UUID is invalid." }

        val host = uri.host?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("The VLESS server address is missing.")
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val query = parseQuery(uri.rawQuery.orEmpty())

        require(query["security"].equals("reality", ignoreCase = true)) {
            "Iran mode currently accepts VLESS + REALITY links only."
        }
        require(query["type"].isNullOrBlank() || query["type"].equals("tcp", ignoreCase = true)) {
            "This VLESS link uses an unsupported transport. TCP is required."
        }
        require(query["encryption"].isNullOrBlank() || query["encryption"] == "none") {
            "Unsupported VLESS encryption setting."
        }

        val serverName = query["sni"]?.takeIf { it.isNotBlank() }
            ?: query["serverName"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("The REALITY server name (sni) is missing.")
        val publicKey = query["pbk"]?.takeIf { it.isNotBlank() }
            ?: query["publicKey"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("The REALITY public key (pbk) is missing.")
        require(REALITY_KEY_PATTERN.matches(publicKey)) { "The REALITY public key is invalid." }

        val shortId = query["sid"] ?: query["shortId"].orEmpty()
        require(SHORT_ID_PATTERN.matches(shortId)) { "The REALITY short ID is invalid." }

        val fingerprint = query["fp"]?.takeIf { it.isNotBlank() } ?: "chrome"
        require(FINGERPRINT_PATTERN.matches(fingerprint)) { "The TLS fingerprint is invalid." }

        val flow = query["flow"].orEmpty()
        require(flow.isBlank() || flow == "xtls-rprx-vision") {
            "This VLESS flow is not supported by this version."
        }

        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", serverName)
            .put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
            .put(
                "reality",
                JSONObject()
                    .put("enabled", true)
                    .put("public_key", publicKey)
                    .put("short_id", shortId)
            )

        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("tls", tls)
        if (flow.isNotBlank()) outbound.put("flow", flow)

        val config = JSONObject()
            .put("log", JSONObject().put("level", "info").put("timestamp", true))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tls")
                                .put("tag", "dns-remote")
                                .put("server", "1.1.1.1")
                                .put("detour", "proxy")
                        )
                    )
                    .put("final", "dns-remote")
                    .put("strategy", "prefer_ipv4")
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                        .put("mtu", 1400)
                        .put("auto_route", true)
                        .put("strict_route", true)
                        .put("stack", "system")
                )
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(outbound)
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
            )
            .put(
                "route",
                JSONObject()
                    .put("auto_detect_interface", true)
                    .put("final", "proxy")
            )

        return IranVpnProfile(
            name = uri.fragment?.let(::decode)?.takeIf { it.isNotBlank() } ?: host,
            serverHost = host,
            serverPort = port,
            configJson = config.toString()
        )
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { item ->
            if (item.isBlank()) return@mapNotNull null
            val parts = item.split("=", limit = 2)
            decode(parts[0]).lowercase() to decode(parts.getOrElse(1) { "" })
        }.toMap()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private val REALITY_KEY_PATTERN = Regex("^[A-Za-z0-9_-]{20,64}$")
    private val SHORT_ID_PATTERN = Regex("^[A-Fa-f0-9]{0,16}$")
    private val FINGERPRINT_PATTERN = Regex("^[A-Za-z0-9_-]{2,24}$")
}
