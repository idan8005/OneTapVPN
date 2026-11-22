package com.raahat.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRoutePolicyTest {
    @Test
    fun addsMissingDefaultRoutesForBothFamiliesWhenAutoRouteIsEnabled() {
        assertTrue(TunnelRoutePolicy.shouldAddIpv4DefaultRoute(true, false))
        assertTrue(TunnelRoutePolicy.shouldAddIpv6DefaultRoute(true, false))
    }

    @Test
    fun doesNotDuplicateExistingDefaultRoutes() {
        assertFalse(TunnelRoutePolicy.shouldAddIpv4DefaultRoute(true, true))
        assertFalse(TunnelRoutePolicy.shouldAddIpv6DefaultRoute(true, true))
    }

    @Test
    fun doesNotAddRoutesWhenAutoRouteIsDisabled() {
        assertFalse(TunnelRoutePolicy.shouldAddIpv4DefaultRoute(false, false))
        assertFalse(TunnelRoutePolicy.shouldAddIpv6DefaultRoute(false, false))
    }

    @Test
    fun recognizesCompressedAndExpandedIpv6DefaultPrefixes() {
        assertTrue(TunnelRoutePolicy.isIpv6DefaultRoute("::", 0))
        assertTrue(TunnelRoutePolicy.isIpv6DefaultRoute("0:0:0:0:0:0:0:0", 0))
        assertFalse(TunnelRoutePolicy.isIpv6DefaultRoute("::", 1))
        assertFalse(TunnelRoutePolicy.isIpv6DefaultRoute("2001:db8::", 0))
    }

    @Test
    fun recognizesOnlyIpv4ZeroPrefixAsIpv4DefaultRoute() {
        assertTrue(TunnelRoutePolicy.isIpv4DefaultRoute("0.0.0.0", 0))
        assertFalse(TunnelRoutePolicy.isIpv4DefaultRoute("0.0.0.0", 1))
        assertFalse(TunnelRoutePolicy.isIpv4DefaultRoute("192.0.2.0", 0))
        assertFalse(TunnelRoutePolicy.isIpv4DefaultRoute("::", 0))
    }
}
