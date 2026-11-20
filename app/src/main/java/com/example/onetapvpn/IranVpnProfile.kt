package com.example.onetapvpn

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class IranVpnProfile(
    val name: String,
    val serverHost: String,
    val serverPort: Int,
    val configJson: String,
    val candidateCount: Int = 1,
    val sourceCount: Int = 1,
    val pingMs: Int = -1,
    val speedMbps: Double = -1.0,
    val healthStatus: String = "unknown"
)

internal object IranVpnProfileParser {
    fun parse(link: String): IranVpnProfile {
        val node = parseNode(link)
        return createProfile(listOf(node), sourceCount = 1, automaticSelection = false)
    }

    fun parseCandidates(
        links: List<String>,
        sourceCount: Int
    ): List<IranVpnProfile> {
        val nodes = links.asSequence()
            .mapNotNull { link -> runCatching { parseNode(link) }.getOrNull() }
            .distinctBy { it.outbound.toString() }
            .take(MAX_AUTO_NODES)
            .toList()

        return nodes.map { node ->
            createProfile(listOf(node), sourceCount.coerceAtLeast(1), automaticSelection = false)
        }
    }

    fun parseAutomatic(
        links: List<String>,
        sourceCount: Int
    ): IranVpnProfile {
        val nodes = links.asSequence()
            .mapNotNull { link -> runCatching { parseNode(link) }.getOrNull() }
            .distinctBy { it.outbound.toString() }
            .take(MAX_AUTO_NODES)
            .toList()

        require(nodes.isNotEmpty()) {
            "No compatible VLESS + REALITY TCP servers were found in the public feeds."
        }
        return createProfile(nodes, sourceCount.coerceAtLeast(1), automaticSelection = true)
    }

    private fun parseNode(link: String): ParsedNode {
        val value = link.trim().trim('"', '\'').replace("&amp;", "&", ignoreCase = true)
        require(value.startsWith("vless://", ignoreCase = true)) {
            "Only VLESS links are supported in Iran mode."
        }

        val uri = try {
            URI(encodeUnsafeFragment(value))
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

        val security = query["security"]?.lowercase().orEmpty()
        require(security == "reality" || security == "tls") {
            "Only encrypted VLESS + REALITY or VLESS + TLS links are supported."
        }
        val transport = query["type"]?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
        require(transport in setOf("tcp", "raw", "ws", "grpc", "http", "httpupgrade")) {
            "This VLESS link uses an unsupported transport."
        }
        require(query["encryption"].isNullOrBlank() || query["encryption"] == "none") {
            "Unsupported VLESS encryption setting."
        }

        val serverName = query["sni"]?.takeIf { it.isNotBlank() }
            ?: query["servername"]?.takeIf { it.isNotBlank() }
            ?: query["host"]?.substringBefore(",")?.takeIf { it.isNotBlank() }
            ?: if (security == "tls") host else
                throw IllegalArgumentException("The REALITY server name (sni) is missing.")
        require(SERVER_NAME_PATTERN.matches(serverName)) {
            "The TLS/REALITY server name is invalid."
        }

        val fingerprint = query["fp"]?.takeIf { it.isNotBlank() } ?: "chrome"
        require(FINGERPRINT_PATTERN.matches(fingerprint)) {
            "The TLS fingerprint is invalid."
        }
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", serverName)
            .put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))

        if (security == "reality") {
            val publicKey = query["pbk"]?.takeIf { it.isNotBlank() }
                ?: query["publickey"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("The REALITY public key (pbk) is missing.")
            require(REALITY_KEY_PATTERN.matches(publicKey)) {
                "The REALITY public key is invalid."
            }
            val shortId = query["sid"] ?: query["shortid"].orEmpty()
            require(SHORT_ID_PATTERN.matches(shortId)) {
                "The REALITY short ID is invalid."
            }
            tls.put(
                "reality",
                JSONObject()
                    .put("enabled", true)
                    .put("public_key", publicKey)
                    .put("short_id", shortId)
            )
        }

        val flow = query["flow"].orEmpty()
        require(flow.isBlank() || (
            flow == "xtls-rprx-vision" &&
                security == "reality" &&
                (transport == "tcp" || transport == "raw")
        )) {
            "XTLS Vision is supported only with VLESS + REALITY over TCP."
        }

        val outbound = JSONObject()
            .put("type", "vless")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("domain_resolver", "dns-local")
            .put("tls", tls)
        if (flow.isNotBlank()) outbound.put("flow", flow)

        when (transport) {
            "ws" -> {
                val ws = JSONObject()
                    .put("type", "ws")
                    .put("path", query["path"]?.takeIf { it.isNotBlank() } ?: "/")
                val hostHeader = query["host"]?.substringBefore(",")?.takeIf { it.isNotBlank() }
                if (hostHeader != null) ws.put("headers", JSONObject().put("Host", hostHeader))
                outbound.put("transport", ws)
            }
            "grpc" -> {
                val serviceName = query["servicename"]?.takeIf { it.isNotBlank() }
                    ?: query["service_name"]?.takeIf { it.isNotBlank() }
                    ?: query["service"]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("The gRPC service name is missing.")
                outbound.put(
                    "transport",
                    JSONObject().put("type", "grpc").put("service_name", serviceName)
                )
            }
            "http" -> {
                val http = JSONObject()
                    .put("type", "http")
                    .put("path", query["path"]?.takeIf { it.isNotBlank() } ?: "/")
                val hosts = query["host"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                if (!hosts.isNullOrEmpty()) http.put("host", JSONArray(hosts))
                outbound.put("transport", http)
            }
            "httpupgrade" -> {
                val upgrade = JSONObject()
                    .put("type", "httpupgrade")
                    .put("path", query["path"]?.takeIf { it.isNotBlank() } ?: "/")
                query["host"]?.takeIf { it.isNotBlank() }?.let { upgrade.put("host", it) }
                outbound.put("transport", upgrade)
            }
        }

        val name = uri.rawFragment?.let { runCatching { decode(it) }.getOrNull() }
            ?.replace(Regex("[\\p{Cntrl}]+"), " ")
            ?.take(40)
            ?.takeIf { it.isNotBlank() }
            ?: host

        return ParsedNode(name, host, port, uuid, outbound)
    }

    private fun createProfile(
        nodes: List<ParsedNode>,
        sourceCount: Int,
        automaticSelection: Boolean
    ): IranVpnProfile {
        val outbounds = JSONArray()
        val nodeTags = mutableListOf<String>()

        nodes.forEachIndexed { index, node ->
            val tag = "node-${index + 1}"
            nodeTags += tag
            outbounds.put(JSONObject(node.outbound.toString()).put("tag", tag))
        }

        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val selectorOptions = JSONArray()
        if (automaticSelection) {
            outbounds.put(
                JSONObject()
                    .put("type", "urltest")
                    .put("tag", "auto")
                    .put("outbounds", JSONArray(nodeTags))
                    .put("url", URL_TEST_ENDPOINT)
                    .put("interval", "30s")
                    .put("tolerance", 25)
                    .put("idle_timeout", "4m")
            )
            selectorOptions.put("auto")
        }
        nodeTags.forEach(selectorOptions::put)
        outbounds.put(
            JSONObject()
                .put("type", "selector")
                .put("tag", "proxy")
                .put("outbounds", selectorOptions)
        )
        val finalOutbound = "proxy"

        val config = JSONObject()
            .put("log", JSONObject().put("level", "info").put("timestamp", true))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "local")
                                .put("tag", "dns-local")
                        )
                    )
                    .put("final", "dns-local")
                    .put("strategy", "prefer_ipv4")
            )
            .put(
                "inbounds",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "tun")
                            .put("tag", "tun-in")
                            .put(
                                "address",
                                JSONArray()
                                    .put("172.19.0.1/30")
                                    .put("fdfe:dcba:9876::1/126")
                            )
                            .put("mtu", 1400)
                            .put("auto_route", true)
                            .put("strict_route", true)
                            .put("stack", "system")
                    )
                    .put(
                        JSONObject()
                            .put("type", "mixed")
                            .put("tag", "local-speedtest")
                            .put("listen", "127.0.0.1")
                            .put("listen_port", 2080)
                    )
            )
            .put("outbounds", outbounds)
            .put(
                "route",
                JSONObject()
                    .put(
                        "rules",
                        JSONArray().put(
                            JSONObject()
                                .put("protocol", "dns")
                                .put("action", "hijack-dns")
                        )
                    )
                    .put("auto_detect_interface", true)
                    .put("final", finalOutbound)
            )

        val controllerSecret = UUID.randomUUID().toString().replace("-", "")
        config.put(
            "experimental",
            JSONObject().put(
                "clash_api",
                JSONObject()
                    .put("external_controller", "127.0.0.1:19090")
                    .put("secret", controllerSecret)
            )
        )

        val first = nodes.first()
        return IranVpnProfile(
            name = if (automaticSelection) "Auto · ${nodes.size} candidates" else first.name,
            serverHost = if (automaticSelection) "auto" else first.host,
            serverPort = if (automaticSelection) 0 else first.port,
            configJson = config.toString(),
            candidateCount = nodes.size,
            sourceCount = sourceCount
        )
    }

    private fun encodeUnsafeFragment(value: String): String {
        val hashIndex = value.indexOf('#')
        if (hashIndex < 0) return value
        val fragment = value.substring(hashIndex + 1)
        val needsEncoding = fragment.any { it.isWhitespace() || it.code > 0x7E || it == '|' }
        if (!needsEncoding) return value
        val encoded = URLEncoder.encode(fragment, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return value.substring(0, hashIndex + 1) + encoded
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { item ->
            if (item.isBlank()) return@mapNotNull null
            val parts = item.split("=", limit = 2)
            runCatching {
                decode(parts[0]).lowercase() to decode(parts.getOrElse(1) { "" })
            }.getOrNull()
        }.toMap()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private data class ParsedNode(
        val name: String,
        val host: String,
        val port: Int,
        val uuid: String,
        val outbound: JSONObject
    )

    private const val MAX_AUTO_NODES = 45
    const val URL_TEST_ENDPOINT = "https://cp.cloudflare.com/generate_204"
    private val SERVER_NAME_PATTERN = Regex("^[A-Za-z0-9.-]{1,253}$")
    private val REALITY_KEY_PATTERN = Regex("^[A-Za-z0-9_-]{20,64}$")
    private val SHORT_ID_PATTERN = Regex("^[A-Fa-f0-9]{0,16}$")
    private val FINGERPRINT_PATTERN = Regex("^[A-Za-z0-9_-]{2,24}$")
}
