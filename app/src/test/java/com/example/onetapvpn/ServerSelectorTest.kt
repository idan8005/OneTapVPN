package com.example.onetapvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSelectorTest {
    private val warp = """
        [Interface]
        PrivateKey = AAAA
        Address = 172.16.0.2/32

        [Peer]
        PublicKey = BBBB
        AllowedIPs = 0.0.0.0/0
        Endpoint = 162.159.192.1:2408
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun recognisesAWarpProfile() {
        assertTrue(ServerSelector.isWarpProfile(warp))
        assertTrue(ServerSelector.isWarpProfile(warp.replace("162.159.192.1:2408", "engage.cloudflareclient.com:2408")))
    }

    @Test
    fun leavesCustomServersAndMultiPeerProfilesAlone() {
        assertFalse(ServerSelector.isWarpProfile(warp.replace("162.159.192.1:2408", "192.0.2.1:51820")))
        assertFalse(ServerSelector.isWarpProfile(warp + "\n\n[Peer]\nPublicKey = CCCC\nAllowedIPs = 10.0.0.0/8"))
    }

    @Test
    fun replacesOnlyTheEndpointValue() {
        val updated = ServerSelector.withEndpoint(warp, "188.114.97.1:2408")
        assertEquals("188.114.97.1:2408", ServerSelector.currentEndpoint(updated))
        assertTrue(updated.contains("PersistentKeepalive = 25"))
        assertTrue(updated.contains("PublicKey = BBBB"))
    }

    @Test
    fun ordersPreferredFirstWithoutDuplicates() {
        val order = ServerSelector.orderedCandidates(warp, "188.114.98.1:2408")
        assertEquals("188.114.98.1:2408", order.first())
        assertEquals("162.159.192.1:2408", order[1])
        assertEquals(order.size, order.toSet().size)
    }
}
