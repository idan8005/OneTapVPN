package com.raahat.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class FreeServerSelectionPolicyTest {
    private fun server(
        id: String,
        transport: String,
        sourcePing: Int,
        probeLatency: Int?,
        quality: Long,
        sessions: Int = 10
    ): FreeServer {
        val host = "$id.opengw.net"
        val protocol = if (transport.equals("TCP", true)) "tcp-client" else "udp"
        val content = """
            client
            dev tun
            proto $protocol
            remote $host 1194
            auth-user-pass
            <ca>
            -----BEGIN CERTIFICATE-----
            TEST-CA
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()
        val profile = OpenVPNProfileParser.parse(content, id, "8.8.8.8")
        return FreeServer(
            id = id, name = id, countryName = "Test", countryCode = "JP",
            endpointIp = "8.8.8.8", sourcePingMs = sourcePing,
            probeLatencyMs = probeLatency, transport = transport,
            qualityScore = quality, activeSessions = sessions, profile = profile
        )
    }

    @Test
    fun prefersFreshlyReachableTcpAndUsesMeasuredLatencyBeforeSourcePing() {
        val udp = server("udp-relay", "UDP", 1, null, 9_000)
        val slowTcp = server("slow-tcp", "TCP", 5, 80, 8_000)
        val fastTcp = server("fast-tcp", "TCP", 90, 18, 1_000)
        val unprobedTcp = server("unprobed-tcp", "TCP", 2, null, 10_000)
        val ordered = FreeServerSelectionPolicy.sortForConnection(
            listOf(udp, slowTcp, unprobedTcp, fastTcp)
        )
        assertEquals(
            listOf("fast-tcp", "slow-tcp", "udp-relay", "unprobed-tcp"),
            ordered.map { it.id }
        )
    }

    @Test
    fun ordersUdpFallbacksByReportedPingThenQualityAndLoad() {
        val lowerQuality = server("udp-a", "UDP", 20, null, 500, 10)
        val higherQuality = server("udp-b", "UDP", 20, null, 900, 20)
        val fewerSessions = server("udp-c", "UDP", 20, null, 900, 5)
        val ordered = FreeServerSelectionPolicy.sortForConnection(
            listOf(lowerQuality, higherQuality, fewerSessions)
        )
        assertEquals(listOf("udp-c", "udp-b", "udp-a"), ordered.map { it.id })
    }
}
