package com.raahat.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android-side full-tunnel route policy.
 *
 * Ensures both address families have an OS-level default route to the VPN TUN
 * when the engine supplies only partial routes. This prevents unmatched traffic
 * from falling back to the device's physical network while the VPN is active.
 */
internal object TunnelRoutePolicy {
    fun shouldAddIpv4DefaultRoute(autoRoute: Boolean, hasIpv4DefaultRoute: Boolean): Boolean =
        autoRoute && !hasIpv4DefaultRoute

    fun shouldAddIpv6DefaultRoute(autoRoute: Boolean, hasIpv6DefaultRoute: Boolean): Boolean =
        autoRoute && !hasIpv6DefaultRoute

    fun isIpv4DefaultRoute(address: String, prefix: Int): Boolean {
        if (prefix != 0 || !address.contains('.')) return false
        val parsed = runCatching {
            InetAddress.getByName(address) as? Inet4Address
        }.getOrNull() ?: return false
        return parsed.isAnyLocalAddress
    }

    fun isIpv6DefaultRoute(address: String, prefix: Int): Boolean {
        if (prefix != 0 || !address.contains(':')) return false
        val parsed = runCatching {
            InetAddress.getByName(address) as? Inet6Address
        }.getOrNull() ?: return false
        return parsed.isAnyLocalAddress
    }
}
