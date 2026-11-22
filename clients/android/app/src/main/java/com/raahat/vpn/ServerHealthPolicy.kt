package com.raahat.vpn

/**
 * Short-lived backoff for public community relays that fail a real connection attempt.
 * Only a timestamp is persisted by the caller; profile contents and credentials are never stored.
 */
internal object ServerHealthPolicy {
    const val FAILURE_COOLDOWN_MS = 15L * 60L * 1000L

    fun isCoolingDown(failedAtMillis: Long, nowMillis: Long): Boolean {
        if (failedAtMillis <= 0L) return false
        return if (failedAtMillis > nowMillis) {
            // A small clock rollback must not immediately re-enable a known-bad relay.
            failedAtMillis - nowMillis <= FAILURE_COOLDOWN_MS
        } else {
            nowMillis - failedAtMillis < FAILURE_COOLDOWN_MS
        }
    }
}
