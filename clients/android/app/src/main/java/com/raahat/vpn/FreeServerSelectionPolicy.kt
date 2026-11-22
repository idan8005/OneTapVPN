package com.raahat.vpn

/**
 * Orders public relays by evidence quality before the first real tunnel attempt.
 * TCP rows have passed a fresh socket probe; UDP cannot be validated by TCP and
 * remains a fallback until a real OpenVPN handshake and tunnel probe.
 */
internal object FreeServerSelectionPolicy {
    fun sortForConnection(servers: List<FreeServer>): List<FreeServer> =
        servers.sortedWith(
            compareBy<FreeServer> { connectionTier(it) }
                .thenBy {
                    if (connectionTier(it) == 0) it.probeLatencyMs ?: Int.MAX_VALUE
                    else it.sourcePingMs
                }
                .thenBy { it.sourcePingMs }
                .thenByDescending { it.qualityScore }
                .thenBy { it.activeSessions }
                .thenBy { it.id }
        )

    private fun connectionTier(server: FreeServer): Int = when {
        server.transport.equals("TCP", ignoreCase = true) && server.probeLatencyMs != null -> 0
        server.transport.equals("UDP", ignoreCase = true) -> 1
        else -> 2
    }
}
