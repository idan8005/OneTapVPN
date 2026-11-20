package com.example.onetapvpn

import com.wireguard.crypto.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireGuardProfileValidatorTest {
    @Test
    fun acceptsAValidFullTunnelProfile() {
        val config = WireGuardProfileValidator.parse(validProfile())
        assertEquals(1, config.getPeers().size)
        assertEquals(1, config.getInterface().getAddresses().size)
    }

    @Test
    fun rejectsAProfileWithoutPeers() {
        val privateKey = KeyPair().privateKey.toBase64()
        val configText = """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.7.0.2/32
        """.trimIndent()

        assertThrows(Exception::class.java) {
            WireGuardProfileValidator.parse(configText)
        }
    }

    @Test
    fun rejectsAnEmptyProfile() {
        assertThrows(Exception::class.java) {
            WireGuardProfileValidator.parse("  ")
        }
    }

    @Test
    fun rejectsProfilesAboveTheSizeLimit() {
        val oversized = "x".repeat(WireGuardProfileValidator.MAX_PROFILE_CHARS + 1)
        assertThrows(Exception::class.java) {
            WireGuardProfileValidator.parse(oversized)
        }
    }

    private fun validProfile(): String {
        val interfaceKey = KeyPair()
        val peerKey = KeyPair()
        return """
            [Interface]
            PrivateKey = ${interfaceKey.privateKey.toBase64()}
            Address = 10.7.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = ${peerKey.publicKey.toBase64()}
            AllowedIPs = 0.0.0.0/0
            Endpoint = 192.0.2.1:51820
        """.trimIndent()
    }
}
