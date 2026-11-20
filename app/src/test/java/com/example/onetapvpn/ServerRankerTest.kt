package com.example.onetapvpn

import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRankerTest {
    @Test
    fun fasterLowLatencyHealthyServerRanksAboveSlowerOverloadedServer() {
        val fast = VpnServer(
            hostName = "fast.example",
            ip = "198.51.100.10",
            score = 500_000,
            pingMs = 24,
            speedBitsPerSecond = 650_000_000,
            country = "Example",
            countryCode = "EX",
            activeSessions = 18,
            uptimeMs = 7L * 24L * 60L * 60L * 1000L,
            operator = "Volunteer",
            configBase64 = "dGVzdA=="
        )
        val slow = VpnServer(
            hostName = "slow.example",
            ip = "198.51.100.20",
            score = 30_000,
            pingMs = 260,
            speedBitsPerSecond = 8_000_000,
            country = "Example",
            countryCode = "EX",
            activeSessions = 85,
            uptimeMs = 2L * 60L * 60L * 1000L,
            operator = "Volunteer",
            configBase64 = "dGVzdA=="
        )

        assertTrue(ServerRanker.score(fast) > ServerRanker.score(slow))
    }

    @Test
    fun speedIsDisplayedInMegabitsPerSecond() {
        val server = VpnServer(
            hostName = "speed.example",
            ip = "203.0.113.20",
            score = 1,
            pingMs = 20,
            speedBitsPerSecond = 250_000_000,
            country = "Example",
            countryCode = "EX",
            activeSessions = 1,
            uptimeMs = 1,
            operator = "Volunteer",
            configBase64 = "dGVzdA=="
        )

        assertTrue(kotlin.math.abs(server.speedMbps - 250.0) < 0.001)
    }
}
