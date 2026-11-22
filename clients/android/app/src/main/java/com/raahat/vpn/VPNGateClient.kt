package com.raahat.vpn

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class FreeServer(
    val id: String,
    val name: String,
    val countryName: String,
    val countryCode: String,
    val endpointIp: String,
    val sourcePingMs: Int,
    val probeLatencyMs: Int?,
    val transport: String,
    val qualityScore: Long,
    val activeSessions: Int,
    val profile: OpenVPNClientProfile
)

class VPNGateClient {
    fun discover(): List<FreeServer> {
        val body = downloadDirectory()
        val candidates = parseCandidates(body)
        if (candidates.isEmpty()) {
            throw IllegalStateException("منبع VPN Gate هیچ پروفایل معتبر OpenVPN برنگرداند.")
        }
        val reachable = probeCandidates(candidates)
        if (reachable.isEmpty()) {
            throw IllegalStateException("در حال حاضر هیچ کاندید قابل آزمایشی در فهرست باقی نمانده است.")
        }
        return reachable
    }

    private fun downloadDirectory(): String {
        val url = URL(API_URL)
        require(url.protocol == "https" && url.host == "www.vpngate.net") {
            "منبع سرور باید وب‌سایت رسمی VPN Gate باشد."
        }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/plain")
            connection.setRequestProperty("User-Agent", "RaahatVPN-Android/0.3")

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("دریافت فهرست سرورها ناموفق بود (HTTP " + responseCode + ").")
            }

            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_DIRECTORY_BYTES) {
                        throw IllegalStateException("فهرست سرورها بیش از حد مجاز بزرگ است.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(StandardCharsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseCandidates(body: String): List<FreeServer> {
        val lines = body.lineSequence().iterator()
        if (!lines.hasNext() || !lines.next().trim().startsWith("*vpn_servers")) {
            throw IllegalStateException("ساختار پاسخ VPN Gate معتبر نیست.")
        }
        if (!lines.hasNext()) throw IllegalStateException("سرآیند CSV در پاسخ VPN Gate وجود ندارد.")
        val header = parseCsvLine(lines.next().trim().removePrefix("\uFEFF").removePrefix("#"))
        val columns = header.mapIndexed { index, name -> name.trim().lowercase(Locale.ROOT) to index }.toMap()
        val required = listOf(
            "hostname", "ip", "score", "ping", "countrylong", "countryshort",
            "numvpnsessions", "openvpn_configdata_base64"
        )
        required.forEach { require(columns.containsKey(it)) { "ستون " + it + " در فهرست سرورها وجود ندارد." } }

        val candidates = mutableListOf<FreeServer>()
        while (lines.hasNext()) {
            val line = lines.next().trim()
            if (line.isBlank() || line.startsWith("*")) continue
            val row = runCatching { parseCsvLine(line) }.getOrNull() ?: continue
            fun field(name: String): String = row.getOrNull(columns[name] ?: -1)?.trim().orEmpty()

            val host = field("hostname")
            val ip = field("ip")
            val countryCode = field("countryshort").uppercase(Locale.ROOT)
            val countryName = field("countrylong")
            val score = field("score").toLongOrNull() ?: continue
            val ping = field("ping").toIntOrNull() ?: continue
            val sessions = field("numvpnsessions").toIntOrNull() ?: continue
            val encoded = field("openvpn_configdata_base64")

            if (!validHost(host) || !validPublicIPv4(ip) || countryCode.length != 2 ||
                score <= 0 || ping < 0 || sessions < 0 ||
                encoded.isBlank() || encoded.length > MAX_PROFILE_BYTES * 2) continue

            val rawProfile = runCatching {
                String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
            }.getOrNull() ?: continue
            val profile = runCatching {
                OpenVPNProfileParser.parse(rawProfile, host, ip)
            }.getOrNull() ?: continue

            val id = stableId(host, ip, profile.serverPort)
            val name = if (host.contains("vpn", ignoreCase = true)) host else "VPN Gate " + host
            candidates += FreeServer(
                id = id,
                name = name,
                countryName = countryName,
                countryCode = countryCode,
                endpointIp = ip,
                sourcePingMs = ping,
                probeLatencyMs = null,
                transport = profile.network.uppercase(Locale.ROOT),
                qualityScore = score,
                activeSessions = sessions,
                profile = profile
            )
        }

        return candidates
            .sortedWith(compareBy<FreeServer> { it.sourcePingMs }.thenByDescending { it.qualityScore })
            .take(MAX_CANDIDATES)
    }

    private fun probeCandidates(candidates: List<FreeServer>): List<FreeServer> {
        // UDP reachability cannot be established with a TCP connect probe.
        // Keep UDP candidates for a real OpenVPN handshake and HTTPS tunnel test.
        val udpCandidates = candidates.filter { it.profile.network == "udp" }
        val tcpCandidates = candidates.filter { it.profile.network == "tcp" }
        val executor = Executors.newFixedThreadPool(MAX_PARALLEL_PROBES)
        try {
            val tasks = tcpCandidates.map { candidate ->
                Callable {
                    val started = System.nanoTime()
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(candidate.endpointIp, candidate.profile.serverPort), PROBE_TIMEOUT_MS)
                        }
                        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).toInt()
                        candidate.copy(probeLatencyMs = elapsed.coerceAtLeast(1))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val futures = executor.invokeAll(tasks, MAX_PROBE_BATCH_SECONDS, TimeUnit.SECONDS)
            val reachableTCP = futures.mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }
            return (udpCandidates + reachableTCP).sortedWith(
                compareBy<FreeServer> { it.sourcePingMs }
                    .thenBy { it.probeLatencyMs ?: Int.MAX_VALUE }
                    .thenByDescending { it.qualityScore }
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += field.toString()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "رکورد CSV ناقص است." }
        fields += field.toString()
        return fields
    }

    private fun validHost(host: String): Boolean =
        host.length in 1..253 &&
            !host.startsWith(".") && !host.endsWith(".") &&
            host.all { it.isLetterOrDigit() || it == '-' || it == '.' }

    private fun validPublicIPv4(value: String): Boolean {
        val parts = value.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val a = parts[0]
        val b = parts[1]
        val c = parts[2]
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 192 && b == 0 && c == 0 -> false
            a == 192 && b == 0 && c == 2 -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a == 240 -> false
            else -> true
        }
    }

    private fun stableId(host: String, ip: String, port: Int): String {
        val input = ("vpn-gate|" + host + "|" + ip + "|" + port).toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input).take(8)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val API_URL = "https://www.vpngate.net/api/iphone/"
        private const val MAX_DIRECTORY_BYTES = 32 * 1024 * 1024
        private const val MAX_PROFILE_BYTES = 512 * 1024
        private const val MAX_CANDIDATES = 80
        private const val MAX_PARALLEL_PROBES = 12
        private const val PROBE_TIMEOUT_MS = 1800
        private const val MAX_PROBE_BATCH_SECONDS = 25L
    }
}
