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
        assertEquals("dns-local", outbound.getString("domain_resolver"))
        val dns = config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("local", dns.getString("type"))
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
        val urlTest = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .first { it.optString("tag") == "auto" }

        assertEquals(2, profile.candidateCount)
        assertEquals(3, profile.sourceCount)
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertEquals("urltest", urlTest.getString("type"))
        assertEquals(2, urlTest.getJSONArray("outbounds").length())
        assertEquals(IranVpnProfileParser.URL_TEST_ENDPOINT, urlTest.getString("url"))
        assertEquals("30s", urlTest.getString("interval"))
        val selector = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .first { it.optString("tag") == "proxy" }
        assertEquals("selector", selector.getString("type"))
        assertEquals("auto", selector.getJSONArray("outbounds").getString(0))
        val inbounds = config.getJSONArray("inbounds")
        assertEquals("tun", inbounds.getJSONObject(0).getString("type"))
        assertEquals("mixed", inbounds.getJSONObject(1).getString("type"))
        val dnsRoute = config.getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertEquals("hijack-dns", dnsRoute.getString("action"))


        val controller = config.getJSONObject("experimental").getJSONObject("clash_api")
        assertEquals("127.0.0.1:19090", controller.getString("external_controller"))
        assertEquals(32, controller.getString("secret").length)
    }

    @Test
    fun acceptsVlessTlsTcpWithoutDisablingCertificateValidation() {
        val link =
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443" +
                "?encryption=none&security=tls&sni=www.example.com&fp=chrome&type=tcp#TLS"
        val outbound = JSONObject(IranVpnProfileParser.parse(link).configJson)
            .getJSONArray("outbounds").getJSONObject(0)
        val tls = outbound.getJSONObject("tls")
        assertEquals(true, tls.getBoolean("enabled"))
        assertEquals("www.example.com", tls.getString("server_name"))
        org.junit.Assert.assertFalse(tls.optBoolean("insecure", false))
        org.junit.Assert.assertFalse(tls.has("reality"))
    }

    @Test
    fun acceptsVlessTlsWebSocketTransport() {
        val link =
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443" +
                "?encryption=none&security=tls&sni=cdn.example.com&host=edge.example.net" +
                "&path=%2Fws&type=ws#WS"
        val outbound = JSONObject(IranVpnProfileParser.parse(link).configJson)
            .getJSONArray("outbounds").getJSONObject(0)
        val transport = outbound.getJSONObject("transport")
        assertEquals("ws", transport.getString("type"))
        assertEquals("/ws", transport.getString("path"))
        assertEquals("edge.example.net", transport.getJSONObject("headers").getString("Host"))
    }

    @Test
    fun acceptsVlessRealityGrpcTransport() {
        val link =
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443" +
                "?encryption=none&security=reality&sni=www.example.com&fp=chrome" +
                "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123abcd" +
                "&type=grpc&serviceName=cloud-grpc#GRPC"
        val outbound = JSONObject(IranVpnProfileParser.parse(link).configJson)
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("grpc", outbound.getJSONObject("transport").getString("type"))
        assertEquals("cloud-grpc", outbound.getJSONObject("transport").getString("service_name"))
    }

    @Test
    fun rejectsVlessWithoutEncryptionOrUnsupportedTransport() {
        val unencrypted = validLink.replace("security=reality", "security=none")
        assertThrows(IllegalArgumentException::class.java) {
            IranVpnProfileParser.parse(unencrypted)
        }
        val unsupported = validLink.replace("type=tcp", "type=xhttp")
        assertThrows(IllegalArgumentException::class.java) {
            IranVpnProfileParser.parse(unsupported)
        }
    }

    @Test
    fun acceptsServerNamesWithSpacesAndUnicodeInUriFragment() {
        val link = validLink.substringBefore("#") + "#US 🇺🇸 | test server"
        val profile = IranVpnProfileParser.parse(link)
        assertEquals("US 🇺🇸 | test server", profile.name)
    }

    @Test
    fun returnsIndividualProfilesForManualServerSelection() {
        val secondLink = validLink.replace("example.com:443", "another.example.net:8443")
            .replace("www.example.com", "www.another.example.net")
            .replace("#Iran", "#Iran2")

        val servers = IranVpnProfileParser.parseCandidates(
            listOf(validLink, secondLink, validLink, "vmess://unsupported"),
            sourceCount = 2
        )

        assertEquals(2, servers.size)
        assertEquals("example.com", servers[0].serverHost)
        assertEquals("another.example.net", servers[1].serverHost)
        assertEquals("proxy", JSONObject(servers[1].configJson)
            .getJSONObject("route").getString("final"))
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
    fun acceptsEightByteRealityShortId() {
        val link = validLink.replace("sid=0123abcd", "sid=0123456789abcdef")
        val profile = IranVpnProfileParser.parse(link)
        val outbound = JSONObject(profile.configJson)
            .getJSONArray("outbounds")
            .getJSONObject(0)
        assertEquals(
            "0123456789abcdef",
            outbound.getJSONObject("tls").getJSONObject("reality").getString("short_id")
        )
    }

    @Test
    fun acceptsVlessRealityWebSocketTransport() {
        val link = validLink.replace("type=tcp", "type=ws")
        val outbound = JSONObject(IranVpnProfileParser.parse(link).configJson)
            .getJSONArray("outbounds").getJSONObject(0)
        val transport = outbound.getJSONObject("transport")
        assertEquals("ws", transport.getString("type"))
        assertEquals("/", transport.getString("path"))
    }
}
