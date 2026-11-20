package com.example.onetapvpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IranVpnProfileParserTest {
    private val validLink =
        "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443" +
            "?encryption=none&security=reality&sni=www.example.com&fp=chrome" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123abcd&type=tcp#Iran"

    @Test
    fun parsesVlessRealityProfileAndBuildsFullTunnelConfig() {
        val profile = IranVpnProfileParser.parse(validLink)
        val config = JSONObject(profile.configJson)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("Iran", profile.name)
        assertEquals("example.com", profile.serverHost)
        assertEquals(443, profile.serverPort)
        assertEquals("vless", outbound.getString("type"))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", outbound.getString("uuid"))
        assertEquals("www.example.com", outbound.getJSONObject("tls").getString("server_name"))
        assertEquals("dns-remote", outbound.getString("domain_resolver"))
        val dns = config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("cloudflare-dns.com", dns.getJSONObject("tls").getString("server_name"))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
    }

    @Test
    fun rejectsRealityProfileWithoutPublicKey() {
        val invalid = validLink.replace(
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            ""
        )
        assertThrows(IllegalArgumentException::class.java) {
            IranVpnProfileParser.parse(invalid)
        }
    }

    @Test
    fun buildsAutomaticUrlTestGroupFromMultiplePublicNodes() {
        val secondLink = validLink.replace("example.com:443", "another.example.net:8443")
            .replace("www.example.com", "www.another.example.net")
            .replace("#Iran", "#Iran2")

        val profile = IranVpnProfileParser.parseAutomatic(
            listOf(validLink, secondLink, validLink),
            sourceCount = 3
        )
        val config = JSONObject(profile.configJson)
        val outbounds = config.getJSONArray("outbounds")
        val urlTest = outbounds.getJSONObject(3)

        assertEquals(2, profile.candidateCount)
        assertEquals(3, profile.sourceCount)
        assertEquals("auto", config.getJSONObject("route").getString("final"))
        assertEquals("urltest", urlTest.getString("type"))
        assertEquals(2, urlTest.getJSONArray("outbounds").length())
        assertEquals(IranVpnProfileParser.URL_TEST_ENDPOINT, urlTest.getString("url"))
        assertEquals("90s", urlTest.getString("interval"))
    }

    @Test
    fun rejectsAutomaticFeedWithoutCompatibleRealityNodes() {
        assertThrows(IllegalArgumentException::class.java) {
            IranVpnProfileParser.parseAutomatic(
                listOf("vmess://not-a-vless-node", "vless://broken"),
                sourceCount = 2
            )
        }
    }

    @Test
    fun acceptsRawTcpRealityTransport() {
        val rawLink = validLink.replace("type=tcp", "type=raw")
        val profile = IranVpnProfileParser.parse(rawLink)
        assertEquals("example.com", profile.serverHost)
    }

    @Test
    fun rejectsUnsupportedWebSocketTransport() {
        val invalid = validLink.replace("type=tcp", "type=ws")
        assertThrows(IllegalArgumentException::class.java) {
            IranVpnProfileParser.parse(invalid)
        }
    }
}
