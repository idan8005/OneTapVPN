package com.raahat.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerHealthPolicyTest {
    private val now = 1_795_000_000_000L

    @Test
    fun failedRelayIsSkippedDuringCooldown() {
        assertTrue(ServerHealthPolicy.isCoolingDown(now, now))
        assertTrue(ServerHealthPolicy.isCoolingDown(now - 60_000L, now))
        assertFalse(
            ServerHealthPolicy.isCoolingDown(
                now - ServerHealthPolicy.FAILURE_COOLDOWN_MS,
                now
            )
        )
    }

    @Test
    fun smallClockRollbackDoesNotImmediatelyReenableRelay() {
        assertTrue(ServerHealthPolicy.isCoolingDown(now + 60_000L, now))
        assertFalse(
            ServerHealthPolicy.isCoolingDown(
                now + ServerHealthPolicy.FAILURE_COOLDOWN_MS + 1L,
                now
            )
        )
    }

    @Test
    fun missingOrInvalidFailureTimestampIsNotCoolingDown() {
        assertFalse(ServerHealthPolicy.isCoolingDown(0L, now))
        assertFalse(ServerHealthPolicy.isCoolingDown(-1L, now))
    }
}
