package com.example.onetapvpn

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

internal data class HybridCatalog(
    val plan: String,
    val generatedAt: String,
    val automaticProfile: IranVpnProfile?,
    val servers: List<IranVpnProfile>
)

internal class HybridControlPlaneClient(
    private val baseUrl: String = BuildConfig.RAAHAT_API_BASE_URL
) {
    @Throws(IOException::class)
    fun fetchCatalog(accessToken: String): HybridCatalog {
        val endpoint = catalogEndpoint()
        val token = accessToken.trim()
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) {
            throw IOException("کد دسترسی معتبر نیست.")
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val text = reader.readText()
                if (text.length > MAX_RESPONSE_CHARS) {
                    throw IOException("پاسخ سرویس بیش از اندازه بزرگ است.")
                }
                text
            }.orEmpty()

            if (status != HttpURLConnection.HTTP_OK) {
                val safeMessage = runCatching {
                    JSONObject(body).optString("message").take(160)
                }.getOrNull().orEmpty()
                val message = when (status) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> "کد دسترسی معتبر نیست یا منقضی شده است."
                    HttpURLConnection.HTTP_FORBIDDEN -> "این حساب اجازه دسترسی به سرورهای ویژه را ندارد."
                    HttpURLConnection.HTTP_UNAVAILABLE -> "سرویس سرورهای ویژه موقتاً در دسترس نیست."
                    else -> safeMessage.ifBlank { "دریافت فهرست ویژه ناموفق بود (HTTP $status)." }
                }
                throw IOException(message)
            }
            HybridCatalogParser.parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun catalogEndpoint(): String {
        val configured = baseUrl.trim().trimEnd('/')
        if (configured.isBlank()) {
            throw IOException("نشانی سرویس مرکزی هنوز برای این نسخه تنظیم نشده است.")
        }
        val uri = runCatching { URI(configured) }.getOrNull()
            ?: throw IOException("نشانی سرویس مرکزی معتبر نیست.")
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.query != null || uri.fragment != null
        ) {
            throw IOException("سرویس مرکزی باید از نشانی HTTPS معتبر استفاده کند.")
        }
        return "$configured/api/v1/catalog"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_RESPONSE_CHARS = 1_000_000
        const val MAX_TOKEN_LENGTH = 4096
        const val USER_AGENT = "RaahatVPN-Android"
    }
}

internal object HybridCatalogParser {
    fun parse(body: String): HybridCatalog {
        val root = JSONObject(body)
        val plan = root.optString("plan")
        require(plan == "free" || plan == "premium") { "پلن حساب در پاسخ سرویس معتبر نیست." }
        val generatedAt = root.optString("generatedAt")

        if (plan == "free") {
            return HybridCatalog(plan, generatedAt, null, emptyList())
        }

        val entries = root.optJSONArray("premiumServers") ?: JSONArray()
        val acceptedLinks = mutableListOf<String>()
        val acceptedServers = mutableListOf<IranVpnProfile>()

        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            if (!item.optBoolean("enabled", true)) continue
            if (item.optString("protocol") != "vless-reality") continue

            val link = item.optString("config").trim()
            if (!link.startsWith("vless://", ignoreCase = true)) continue
            val parsed = runCatching { IranVpnProfileParser.parse(link) }.getOrNull() ?: continue

            val host = item.optString("host").trim()
            val port = item.optInt("port", -1)
            if (host.isBlank() || !parsed.serverHost.equals(host, ignoreCase = true) ||
                port !in 1..65535 || parsed.serverPort != port
            ) {
                continue
            }

            val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: parsed.name
            acceptedLinks += link
            acceptedServers += parsed.copy(name = name)
            if (acceptedServers.size >= MAX_PREMIUM_SERVERS) break
        }

        val automatic = if (acceptedLinks.isEmpty()) {
            null
        } else {
            runCatching {
                IranVpnProfileParser.parseAutomatic(acceptedLinks, sourceCount = 1)
                    .copy(name = "Premium · Auto")
            }.getOrNull()
        }
        return HybridCatalog(plan, generatedAt, automatic, acceptedServers)
    }

    private const val MAX_PREMIUM_SERVERS = 60
}
