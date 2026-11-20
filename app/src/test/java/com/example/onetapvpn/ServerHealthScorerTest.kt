package com.example.onetapvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerHealthScorerTest {
    @Test
    fun balancesRealSpeedAndLatencyInsteadOfChoosingPingAlone() {
        val selected = ServerHealthScorer.selectBest(
            listOf(
                ServerQualitySample("very-low-ping", 22, 4.0),
                ServerQualitySample("fast-throughput", 78, 58.0)
            )
        )
        assertEquals("fast-throughput", selected)
    }

    @Test
    fun prefersStableServerWhenMeasuredSpeedIsSimilar() {
        val selected = ServerHealthScorer.selectBest(
            listOf(
                ServerQualitySample("unstable", 42, 24.0, successRate = 0.34, jitterMs = 190),
                ServerQualitySample("stable", 48, 23.5, successRate = 1.0, jitterMs = 7)
            )
        )
        assertEquals("stable", selected)
    }

    @Test
    fun fallsBackToLowestLatencyWhenSpeedSamplesAreUnavailable() {
        val selected = ServerHealthScorer.selectBest(
            listOf(
                ServerQualitySample("slower", 130, -1.0),
                ServerQualitySample("faster", 41, -1.0)
            )
        )
        assertEquals("faster", selected)
    }

    @Test
    fun keepsUnmeasuredRespondingServersAsFallbackCandidates() {
        val ranked = ServerHealthScorer.rank(
            listOf(
                ServerQualitySample("fast-measured", 18, 72.0),
                ServerQualitySample("slower-measured", 52, 31.0),
                ServerQualitySample("not-speed-tested", 34, -1.0),
                ServerQualitySample("offline", -1, -1.0)
            )
        )
        assertEquals(
            listOf("fast-measured", "slower-measured", "not-speed-tested"),
            ranked
        )
    }

    @Test
    fun ignoresOfflineServersAndReturnsNullWhenAllAreOffline() {
        assertEquals(
            "online",
            ServerHealthScorer.selectBest(
                listOf(
                    ServerQualitySample("offline", -1, -1.0),
                    ServerQualitySample("online", 65, -1.0)
                )
            )
        )
        assertNull(
            ServerHealthScorer.selectBest(
                listOf(ServerQualitySample("offline", -1, -1.0))
            )
        )
    }
}
