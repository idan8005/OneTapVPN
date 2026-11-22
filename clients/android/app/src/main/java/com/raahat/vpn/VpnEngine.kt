package com.raahat.vpn

/**
 * Platform tunnel lifecycle contract. Implementations must report Connected
 * only after the OS tunnel is up and traffic verification has succeeded.
 */
interface VpnEngine {
    val isAvailable: Boolean
    suspend fun connect(serverId: String)
    suspend fun disconnect()
}
