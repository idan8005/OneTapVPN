package com.example.onetapvpn

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal data class IranDiscoveryResult(
    val profile: IranVpnProfile,
    val sourceCount: Int,
    val rawLineCount: Int
)

/**
 * Reads independent public feeds and extracts VLESS + REALITY TCP links.
 * The feeds are untrusted input: every candidate is strictly parsed before it
 * is admitted to the sing-box URL-test group.
 */
internal class IranServerDiscovery {
    suspend fun discover(): IranDiscoveryResult = withContext(Dispatchers.IO) {
        val fetched = coroutineScope {
            FEEDS.map { feed ->
                async {
                    fetchFirstAvailable(feed.mirrors)?.let { body ->
                        feed to extractVlessLinks(body)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Interleave feeds before the parser caps the candidate set, so a
        // single large source cannot crowd every other provider out.
        val sourceCandidates = fetched.map { it.second.distinct() }
        val links = buildList {
            var index = 0
            while (size < MAX_INTERLEAVED_CANDIDATES &&
                sourceCandidates.any { index < it.size }
            ) {
                sourceCandidates.forEach { candidates ->
                    candidates.getOrNull(index)?.let(::add)
                }
                index++
            }
        }.distinct()
        val profile = IranVpnProfileParser.parseAutomatic(links, fetched.size)
        IranDiscoveryResult(
            profile = profile,
            sourceCount = fetched.size,
            rawLineCount = links.size
        )
    }

    private fun fetchFirstAvailable(urls: List<String>): String? {
        for (url in urls) {
            val body = runCatching { download(url) }.getOrNull()
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    private fun download(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/plain, */*")
            setRequestProperty("User-Agent", "RaahatVPN/1.5.0 (Android)")
            setRequestProperty("Cache-Control", "no-cache")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status from server source")
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_SOURCE_BYTES) {
                throw IOException("Server source is larger than the allowed download size")
            }

            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_SOURCE_BYTES) {
                        throw IOException("Server source exceeded the allowed download size")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray().toString(StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractVlessLinks(payload: String): List<String> {
        val direct = findVlessLines(payload)
        if (direct.isNotEmpty()) return direct

        // Some public feeds publish a base64-encoded subscription. Decode only
        // when the body contains no direct links; malformed feeds are discarded.
        val compact = payload.filterNot(Char::isWhitespace)
        if (compact.length < 24 || !BASE64_PATTERN.matches(compact)) return emptyList()

        val decoded = runCatching {
            String(Base64.decode(compact, Base64.DEFAULT), StandardCharsets.UTF_8)
        }.recoverCatching {
            String(Base64.decode(compact, Base64.URL_SAFE), StandardCharsets.UTF_8)
        }.getOrNull() ?: return emptyList()

        return findVlessLines(decoded)
    }

    private fun findVlessLines(content: String): List<String> =
        content.lineSequence()
            .take(MAX_SOURCE_LINES)
            .map { it.trim().trim('"', '\'') }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .take(MAX_CANDIDATE_LINES_PER_SOURCE)
            .toList()

    private data class Feed(val mirrors: List<String>)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 6_500
        const val READ_TIMEOUT_MS = 9_000
        const val MAX_SOURCE_BYTES = 1_500_000
        const val MAX_INTERLEAVED_CANDIDATES = 160
        const val MAX_SOURCE_LINES = 20_000
        const val MAX_CANDIDATE_LINES_PER_SOURCE = 700
        val BASE64_PATTERN = Regex("^[A-Za-z0-9+/=_-]+$")

        val FEEDS = listOf(
            Feed(
                listOf(
                    "https://raw.githubusercontent.com/mehrtat/vless-collector/main/vless.txt",
                    "https://cdn.jsdelivr.net/gh/mehrtat/vless-collector@main/vless.txt"
                )
            ),
            Feed(
                listOf(
                    "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs.txt",
                    "https://cdn.jsdelivr.net/gh/0xRadikal/Free-v2ray-Configs@main/verified/configs.txt"
                )
            ),
            Feed(
                listOf(
                    "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/best-base64.txt",
                    "https://cdn.jsdelivr.net/gh/morpheusadam/v2ray-config@main/subs/bundles/best-base64.txt"
                )
            ),
            Feed(
                listOf(
                    "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/v2ray-base64.txt",
                    "https://cdn.jsdelivr.net/gh/Au1rxx/free-vpn-subscriptions@main/output/v2ray-base64.txt"
                )
            )
        )
    }
}
