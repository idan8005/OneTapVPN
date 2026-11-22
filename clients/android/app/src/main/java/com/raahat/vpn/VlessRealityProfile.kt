package com.raahat.vpn

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class VlessRealityProfile(
    val id: String,
    val host: String,
    val port: Int,
    val uuid: String,
    val serverName: String,
    val publicKey: String,
    val shortId: String,
    val fingerprint: String,
    val flow: String
) {
    fun toSingBoxConfig(): String {
        val flowField = if (flow.isBlank()) "" else ",\"flow\":" + json(flow)
        return """
            {
              "log": { "level": "warn", "timestamp": true },
              "dns": {
                "servers": [
                  { "type": "udp", "tag": "dns-remote", "server": "1.1.1.1", "server_port": 53 }
                ],
                "final": "dns-remote",
                "strategy": "prefer_ipv4"
              },
              "inbounds": [
                {
                  "type": "tun",
                  "tag": "raahat-tun",
                  "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
                  "mtu": 1400,
                  "auto_route": true,
                  "dns_mode": "hijack",
                  "dns_address": ["172.19.0.2", "fdfe:dcba:9876::2"]
                }
              ],
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "proxy",
                  "server": ${json(host)},
                  "server_port": $port,
                  "uuid": ${json(uuid)}$flowField,
                  "tls": {
                    "enabled": true,
                    "server_name": ${json(serverName)},
                    "utls": { "enabled": true, "fingerprint": ${json(fingerprint)} },
                    "reality": {
                      "enabled": true,
                      "public_key": ${json(publicKey)},
                      "short_id": ${json(shortId)}
                    }
                  }
                },
                { "type": "direct", "tag": "direct" },
                { "type": "block", "tag": "block" }
              ],
              "route": {
                "auto_detect_interface": true,
                "final": "proxy"
              }
            }
        """.trimIndent()
    }

    companion object {
        fun parse(link: String): VlessRealityProfile {
            require(link.length <= 8192) { "لینک سرور بیش از حد طولانی است." }
            val uri = runCatching { URI(link.trim()) }
                .getOrElse { throw IllegalArgumentException("ساختار لینک سرور معتبر نیست.") }

            require(uri.scheme.equals("vless", ignoreCase = true)) {
                "فعلاً فقط لینک VLESS + REALITY پشتیبانی می‌شود."
            }
            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("آدرس سرور در لینک وجود ندارد.")
            val port = uri.port.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("پورت سرور معتبر نیست.")
            val uuid = uri.userInfo?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("شناسه کاربر در لینک وجود ندارد.")
            runCatching { UUID.fromString(uuid) }
                .getOrElse { throw IllegalArgumentException("شناسه کاربر لینک معتبر نیست.") }

            val query = uri.rawQuery.orEmpty()
                .split("&")
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val parts = pair.split("=", limit = 2)
                    decode(parts[0]) to decode(parts.getOrElse(1) { "" })
                }

            require(query["security"].equals("reality", ignoreCase = true)) {
                "این لینک از REALITY استفاده نمی‌کند."
            }
            val transport = query["type"] ?: query["network"] ?: "tcp"
            require(transport.equals("tcp", ignoreCase = true)) {
                "در این نسخه فقط انتقال TCP برای VLESS + REALITY پشتیبانی می‌شود."
            }

            val publicKey = query["pbk"] ?: query["publicKey"]
                ?: throw IllegalArgumentException("کلید عمومی REALITY در لینک وجود ندارد.")
            require(publicKey.isNotBlank()) { "کلید عمومی REALITY خالی است." }

            val serverName = (query["sni"] ?: query["serverName"] ?: host).trim()
            require(serverName.isNotBlank()) { "نام میزبان TLS معتبر نیست." }

            val shortId = query["sid"] ?: query["shortId"] ?: ""
            require(shortId.length <= 16 && shortId.all { it in "0123456789abcdefABCDEF" }) {
                "شناسه کوتاه REALITY معتبر نیست."
            }

            val flow = query["flow"].orEmpty()
            require(flow.isBlank() || flow == "xtls-rprx-vision") {
                "Flow این سرور پشتیبانی نمی‌شود."
            }

            val fingerprint = query["fp"]?.takeIf { it.isNotBlank() } ?: "chrome"
            require(fingerprint.matches(Regex("[A-Za-z0-9_-]{1,32}"))) {
                "Fingerprint معتبر نیست."
            }

            return VlessRealityProfile(
                id = uri.fragment?.takeIf { it.isNotBlank() } ?: host,
                host = host,
                port = port,
                uuid = uuid,
                serverName = serverName,
                publicKey = publicKey,
                shortId = shortId,
                fingerprint = fingerprint,
                flow = flow
            )
        }

        private fun decode(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())

        private fun json(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
    }
}
