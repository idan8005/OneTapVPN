package com.raahat.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessRealityProfileTest {
    private val validLink =
        "vless://11111111-1111-4111-8111-111111111111@edge.example.net:443" +
            "?encryption=none&security=reality&sni=www.example.net&fp=chrome" +
            "&pbk=examplePublicKey&sid=01234567&type=tcp&flow=xtls-rprx-vision#Test"

    @Test
    fun parsesRealityProfileAndBuildsTunConfig() {
        val profile = VlessRealityProfile.parse(validLink)
        assertEquals("edge.example.net", profile.host)
        assertEquals(443, profile.port)
        assertEquals("www.example.net", profile.serverName)
        val config = profile.toSingBoxConfig()
        assertTrue(config.contains("\"type\": \"tun\""))
        assertTrue(config.contains("\"public_key\": \"examplePublicKey\""))
        assertTrue(config.contains("\"short_id\": \"01234567\""))
        assertTrue(config.contains("\"type\": \"vless\""))
        assertTrue(config.contains("\"type\": \"https\""))
        assertTrue(config.contains("\"detour\": \"proxy\""))
        assertTrue(config.contains("\"action\": \"hijack-dns\""))
        assertTrue(config.contains("\"dns_mode\": \"hijack\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherSchemes() {
        VlessRealityProfile.parse("vmess://not-supported")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonRealityProfiles() {
        VlessRealityProfile.parse(
            "vless://11111111-1111-4111-8111-111111111111@edge.example.net:443?security=tls"
        )
    }
}
