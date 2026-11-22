package com.raahat.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthPolicyTest {
    @Test
    fun dnsAndPublicIpv4AreRequiredForConnectionSuccess() {
        val report = TunnelHealthPolicy.evaluate(
            dnsResolved = true,
            ipv4Address = "203.0.113.10",
            ipv6Address = null
        )
        assertFalse(report.isReady)
        assertEquals(ProbeStatus.FAILED, report.ipv4)
    }

    @Test
    fun validPublicIpv4AndDnsPassEvenWhenIpv6IsUnavailable() {
        val report = TunnelHealthPolicy.evaluate(
            dnsResolved = true,
            ipv4Address = "8.8.4.4",
            ipv6Address = null
        )
        assertTrue(report.isReady)
        assertEquals(ProbeStatus.PASSED, report.dns)
        assertEquals(ProbeStatus.PASSED, report.ipv4)
        assertEquals(ProbeStatus.UNAVAILABLE, report.ipv6)
    }

    @Test
    fun failedDnsPreventsConnectedState() {
        val report = TunnelHealthPolicy.evaluate(
            dnsResolved = false,
            ipv4Address = "1.1.1.1",
            ipv6Address = "2606:4700:4700::1111"
        )
        assertFalse(report.isReady)
        assertEquals(ProbeStatus.FAILED, report.dns)
    }

    @Test
    fun rejectsPrivateAndMalformedIpv4Addresses() {
        assertFalse(TunnelHealthPolicy.isPublicIpv4("192.168.1.4"))
        assertFalse(TunnelHealthPolicy.isPublicIpv4("10.0.0.2"))
        assertFalse(TunnelHealthPolicy.isPublicIpv4("not-an-ip"))
        assertFalse(TunnelHealthPolicy.isPublicIpv4("256.1.1.1"))
        assertFalse(TunnelHealthPolicy.isPublicIpv4(null))
    }

    @Test
    fun acceptsGlobalIpv6AndRejectsLocalIpv6() {
        assertTrue(TunnelHealthPolicy.isPublicIpv6("2606:4700:4700::1111"))
        assertFalse(TunnelHealthPolicy.isPublicIpv6("::1"))
        assertFalse(TunnelHealthPolicy.isPublicIpv6("fe80::1"))
        assertFalse(TunnelHealthPolicy.isPublicIpv6("fd00::1"))
        assertFalse(TunnelHealthPolicy.isPublicIpv6("2001:db8::1"))
        assertFalse(TunnelHealthPolicy.isPublicIpv6("not-an-ip"))
    }

    @Test
    fun ipv6ReachabilityMessageDoesNotClaimLeakProtection() {
        val report = TunnelHealthPolicy.evaluate(
            dnsResolved = true,
            ipv4Address = "1.1.1.1",
            ipv6Address = "2606:4700:4700::1111"
        )
        assertTrue(report.isReady)
        assertTrue(report.toPersianMessage().contains("نبود نشت IPv6 را ثابت نمی‌کند"))
    }
}
