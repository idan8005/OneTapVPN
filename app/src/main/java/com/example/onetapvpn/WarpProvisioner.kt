package com.example.onetapvpn

import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registers a free Cloudflare WARP account and returns a ready-to-use WireGuard config.
 *
 * This is an UNOFFICIAL API: Cloudflare may change or block it at any time.
 * Must be called off the main thread.
 */
object WarpProvisioner {

    private const val API = "https://api.cloudflareclient.com/v0a1922/reg"

    fun provision(): String {
        val keys = KeyPair()
        val publicKey = keys.publicKey.toBase64()
        val privateKey = keys.privateKey.toBase64()

        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
        val body = JSONObject()
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", tos)
            .put("key", publicKey)
            .put("type", "Android")
            .put("locale", "en_US")
            .toString()

        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("User-Agent", "okhttp/3.12.1")
            setRequestProperty("CF-Client-Version", "a-6.3-1922")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
            throw IllegalStateException("WARP registration failed (HTTP $code) $err")
        }
        val json = JSONObject(conn.inputStream.bufferedReader().readText())

        val cfg = json.getJSONObject("config")
        val peer = cfg.getJSONArray("peers").getJSONObject(0)
        val peerKey = peer.getString("public_key")
        val endpoint = peer.getJSONObject("endpoint").optString("host", "engage.cloudflareclient.com:2408")
        val addrs = cfg.getJSONObject("interface").getJSONObject("addresses")
        val v4 = addrs.getString("v4")
        val v6 = addrs.optString("v6", "")

        val address = if (v6.isNotEmpty()) "$v4/32, $v6/128" else "$v4/32"

        return """
            [Interface]
            PrivateKey = $privateKey
            Address = $address
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280

            [Peer]
            PublicKey = $peerKey
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = $endpoint
        """.trimIndent()
    }
}
