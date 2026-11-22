package com.raahat.vpn

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android-side full-tunnel route policy.
 *
 * Even if sing-box supplies only partial IPv6 routes, the Android VPN interface
 * receives an IPv6 default route so unmatched IPv6 destinations cannot fall back
 * to the device's physical network while the VPN is active.
 */
internal object TunnelRoutePolicy {
    fun shouldAddIpv6DefaultRoute(
        autoRoute: Boolean,
        hasIpv6DefaultRoute: Boolean
    ): Boolean = autoRoute && !hasIpv6DefaultRoute

    fun isIpv6DefaultRoute(address: String, prefix: Int): Boolean {
        if (prefix != 0 || !address.contains(':')) return false
        val parsed = runCatching {
            InetAddress.getByName(address) as? Inet6Address
        }.getOrNull() ?: return false
        return parsed.isAnyLocalAddress
    }
}
