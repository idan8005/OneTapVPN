package com.raahat.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRoutePolicyTest {
    @Test
    fun addsIpv6DefaultRouteWhenAutoRouteHasNoDefault() {
        assertTrue(TunnelRoutePolicy.shouldAddIpv6DefaultRoute(true, false))
    }

    @Test
    fun doesNotDuplicateExistingIpv6DefaultRoute() {
        assertFalse(TunnelRoutePolicy.shouldAddIpv6DefaultRoute(true, true))
    }

    @Test
    fun doesNotAddIpv6RouteWhenAutoRouteIsDisabled() {
        assertFalse(TunnelRoutePolicy.shouldAddIpv6DefaultRoute(false, false))
    }

    @Test
    fun recognizesCompressedAndExpandedIpv6DefaultPrefixes() {
        assertTrue(TunnelRoutePolicy.isIpv6DefaultRoute("::", 0))
        assertTrue(TunnelRoutePolicy.isIpv6DefaultRoute("0:0:0:0:0:0:0:0", 0))
        assertFalse(TunnelRoutePolicy.isIpv6DefaultRoute("::", 1))
        assertFalse(TunnelRoutePolicy.isIpv6DefaultRoute("0.0.0.0", 0))
        assertFalse(TunnelRoutePolicy.isIpv6DefaultRoute("2001:db8::", 0))
    }
}
