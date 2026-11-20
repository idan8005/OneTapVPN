package com.example.onetapvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HybridCatalogParserTest {
    private val validLink =
        "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443" +
            "?encryption=none&security=reality&sni=www.example.com&fp=chrome" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123abcd&type=tcp#Premium"

    @Test
    fun freePlanDoesNotExposePremiumProfiles() {
        val catalog = HybridCatalogParser.parse(
            """{"plan":"free","generatedAt":"2026-11-21T00:00:00Z","publicDiscovery":true,"premiumServers":[]}"""
        )
        assertEquals("free", catalog.plan)
        assertNull(catalog.automaticProfile)
        assertEquals(0, catalog.servers.size)
    }

    @Test
    fun premiumPlanBuildsAutomaticAndManualProfilesFromValidNodes() {
        val json = """
            {
              "plan":"premium",
              "generatedAt":"2026-11-21T00:00:00Z",
              "premiumServers":[{
                "id":"eu-01",
                "name":"Europe One",
                "country":"Germany",
                "region":"EU",
                "host":"example.com",
                "port":443,
                "protocol":"vless-reality",
                "config":"$validLink",
                "enabled":true
              }]
            }
        """.trimIndent()
        val catalog = HybridCatalogParser.parse(json)
        assertEquals("premium", catalog.plan)
        assertNotNull(catalog.automaticProfile)
        assertEquals(1, catalog.servers.size)
        assertEquals("Europe One", catalog.servers.first().name)
        assertEquals("example.com", catalog.servers.first().serverHost)
    }

    @Test
    fun rejectsMismatchedServerMetadataAndUnsupportedProtocols() {
        val json = """
            {
              "plan":"premium",
              "premiumServers":[
                {"id":"bad-host","name":"Bad host","host":"other.example","port":443,"protocol":"vless-reality","config":"$validLink","enabled":true},
                {"id":"wg","name":"No WireGuard","host":"example.com","port":443,"protocol":"wireguard","config":"$validLink","enabled":true}
              ]
            }
        """.trimIndent()
        val catalog = HybridCatalogParser.parse(json)
        assertNull(catalog.automaticProfile)
        assertEquals(0, catalog.servers.size)
    }

    @Test
    fun ignoresOpenVpnUntilAnAndroidProfileAdapterExists() {
        val json = """
            {
              "plan":"premium",
              "premiumServers":[{
                "id":"legacy","name":"Legacy OpenVPN","host":"example.com","port":443,
                "protocol":"openvpn","config":"client\\nremote example.com 443","enabled":true
              }]
            }
        """.trimIndent()
        val catalog = HybridCatalogParser.parse(json)
        assertNull(catalog.automaticProfile)
        assertEquals(0, catalog.servers.size)
    }
}
