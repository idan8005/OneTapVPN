package com.example.onetapvpn

internal data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Int,
    val speedBytesPerSecond: Long,
    val country: String,
    val countryCode: String,
    val activeSessions: Long,
    val uptimeMs: Long,
    val operator: String,
    val configBase64: String
) {
    val speedMbps: Double
        get() = speedBytesPerSecond * 8.0 / 1_000_000.0
}
