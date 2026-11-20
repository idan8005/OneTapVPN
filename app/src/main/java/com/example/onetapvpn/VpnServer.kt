package com.example.onetapvpn

internal data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Int,
    /** VPN Gate publishes throughput in bits per second. */
    val speedBitsPerSecond: Long,
    val country: String,
    val countryCode: String,
    val activeSessions: Long,
    val uptimeMs: Long,
    val operator: String,
    val configBase64: String
) {
    val speedMbps: Double
        get() = speedBitsPerSecond / 1_000_000.0
}
