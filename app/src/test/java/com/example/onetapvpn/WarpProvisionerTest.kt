package com.example.onetapvpn

import org.junit.Assert.assertEquals
import org.junit.Test

class WarpProvisionerTest {
    @Test
    fun prefersTheIpv4AddressAndKeepsThePortFromTheHost() {
        assertEquals(
            "162.159.192.1:2408",
            WarpProvisioner.resolveEndpoint("engage.cloudflareclient.com:2408", "162.159.192.1:0")
        )
    }

    @Test
    fun usesTheDefaultPortWhenTheHostHasNone() {
        assertEquals(
            "162.159.192.1:2408",
            WarpProvisioner.resolveEndpoint("engage.cloudflareclient.com", "162.159.192.1")
        )
    }

    @Test
    fun fallsBackToTheHostWhenThereIsNoUsableIpv4() {
        assertEquals(
            "engage.cloudflareclient.com:2408",
            WarpProvisioner.resolveEndpoint("engage.cloudflareclient.com:2408", "")
        )
        assertEquals(
            "engage.cloudflareclient.com:2408",
            WarpProvisioner.resolveEndpoint("engage.cloudflareclient.com:2408", "not-an-ip:0")
        )
    }
}
