package com.example.onetapvpn

import com.wireguard.crypto.KeyPair
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Creates a Cloudflare WARP consumer profile.
 *
 * Cloudflare does not publish this endpoint as a supported third-party API. Registration may be
 * rejected or changed without notice. The generated private key is returned only to the caller
 * and must be stored using [SecureConfigStore]. Call this method on a background thread.
 */
internal object WarpProvisioner {
    private const val REGISTRATION_URL = "https://api.cloudflareclient.com/v0a1922/reg"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_ERROR_BODY_CHARS = 800
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1_500L
    private const val DEFAULT_PORT = 2408
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Retries transient network failures a few times so the user never has to tap again. */
    @Throws(IOException::class, IllegalStateException::class)
    fun provisionWithRetry(attempts: Int = MAX_ATTEMPTS): String {
        var lastError: IOException? = null
        for (attempt in 1..attempts) {
            try {
                return provision()
            } catch (error: IOException) {
                lastError = error
                if (attempt < attempts) Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }
        throw lastError ?: IOException("Cloudflare WARP registration failed.")
    }

    /**
     * Prefers the endpoint's IPv4 address over its host name so connecting does not depend on DNS
     * (resolvers on some networks block or poison cloudflareclient.com). Falls back to the host.
     */
    internal fun resolveEndpoint(host: String, v4: String): String {
        val port = host.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 }
            ?: DEFAULT_PORT
        val ip = v4.trim().substringBefore(':')
        return if (IPV4.matches(ip)) "$ip:$port" else host
    }

    @Throws(IOException::class, IllegalStateException::class)
    fun provision(): String {
        val keyPair = KeyPair()
        val requestBody = buildRegistrationBody(keyPair.publicKey.toBase64())
        val connection = (URL(REGISTRATION_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("User-Agent", "okhttp/3.12.1")
            setRequestProperty("CF-Client-Version", "a-6.3-1922")
        }

        try {
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val responseBody = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText().take(MAX_ERROR_BODY_CHARS) }
                    .orEmpty()
                throw IOException(
                    buildString {
                        append("Cloudflare WARP registration failed (HTTP ")
                        append(responseCode)
                        append(").")
                        if (responseBody.isNotBlank()) append(" ").append(responseBody)
                    }
                )
            }

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return buildWireGuardConfig(JSONObject(responseText), keyPair.privateKey.toBase64())
        } catch (error: JSONException) {
            throw IOException("Cloudflare returned an invalid WARP profile.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRegistrationBody(publicKey: String): String {
        val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return JSONObject()
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", utcFormat.format(Date()))
            .put("key", publicKey)
            .put("type", "Android")
            .put("locale", Locale.US.toString())
            .toString()
    }

    private fun buildWireGuardConfig(response: JSONObject, privateKey: String): String {
        val config = response.optJSONObject("config")
            ?: throw JSONException("The WARP response did not contain a config object.")
        val peers = config.optJSONArray("peers")
            ?: throw JSONException("The WARP response did not contain peers.")
        if (peers.length() == 0) throw JSONException("The WARP response contained no peers.")

        val peer = peers.getJSONObject(0)
        val peerPublicKey = peer.optString("public_key").trim()
        if (peerPublicKey.isEmpty()) throw JSONException("The WARP peer public key is missing.")

        val endpointObject = peer.optJSONObject("endpoint")
            ?: throw JSONException("The WARP endpoint is missing.")
        val endpointHost = endpointObject.optString("host").trim()
        if (endpointHost.isEmpty()) throw JSONException("The WARP endpoint host is missing.")
        val endpoint = resolveEndpoint(endpointHost, endpointObject.optString("v4"))

        val addresses = config.optJSONObject("interface")
            ?.optJSONObject("addresses")
            ?: throw JSONException("The WARP interface addresses are missing.")
        val ipv4 = addresses.optString("v4").trim()
        if (ipv4.isEmpty()) throw JSONException("The WARP IPv4 address is missing.")
        val ipv6 = addresses.optString("v6").trim()
        val interfaceAddress = buildString {
            append(ipv4)
            append("/32")
            if (ipv6.isNotEmpty()) append(", ").append(ipv6).append("/128")
        }
        val allowedIps = if (ipv6.isNotEmpty()) "0.0.0.0/0, ::/0" else "0.0.0.0/0"

        return """
            [Interface]
            PrivateKey = $privateKey
            Address = $interfaceAddress
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280

            [Peer]
            PublicKey = $peerPublicKey
            AllowedIPs = $allowedIps
            Endpoint = $endpoint
            PersistentKeepalive = 25
        """.trimIndent()
    }
}
