package com.raahat.vpn

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenVPNProfileParserTest {
    private val ca = "-----BEGIN CERTIFICATE-----\nTEST-CA-CONTENT\n-----END CERTIFICATE-----"
    private val authKey = "-----BEGIN OpenVPN Static key V1-----\nTEST-STATIC-KEY\n-----END OpenVPN Static key V1-----"
    private val validProfile = """
        client
        dev tun
        remote public-vpn-7.opengw.net 443
        proto tcp-client
        auth-user-pass
        remote-cert-tls server
        cipher AES-256-CBC
        auth SHA1
        key-direction 1
        <ca>
        $ca
        </ca>
        <tls-auth>
        $authKey
        </tls-auth>
    """.trimIndent()

    @Test
    fun parsesInlineCertificatesAndBuildsOpenVPNEndpointConfig() {
        val profile = OpenVPNProfileParser.parse(validProfile, "public-vpn-7", "8.8.8.8")
        assertEquals("public-vpn-7.opengw.net", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("tls_auth", profile.controlWrapType)
        assertEquals("client", profile.controlWrapDirection)
        assertEquals("vpn", profile.username)
        assertEquals("vpn", profile.password)
        assertEquals("AES-256-CBC", profile.dataCiphersFallback)

        val config = profile.toSingBoxConfig()
        assertTrue(config.contains("\"type\":\"openvpn-client\""))
        assertTrue(config.contains("\"server_port\":443"))
        assertTrue(config.contains("\"redirect_gateway\":true"))
        assertTrue(config.contains("\"block_ipv6\":true"))
        assertTrue(config.contains("\"type\":\"openvpn\""))
        assertTrue(config.contains("\"final\":\"ovpn-client\""))
        assertTrue(config.contains("TEST-CA-CONTENT"))
        assertTrue(config.contains("TEST-STATIC-KEY"))
        assertTrue(!config.contains("\"cipher\":\"AES-256-CBC\""))
    }

    @Test
    fun selectsMatchingTcpRemoteWhenProfileHasEarlierUdpRemote() {
        val multiRemote = validProfile.replace(
            "remote public-vpn-7.opengw.net 443",
            "remote public-vpn-7.opengw.net 1194 udp\nremote public-vpn-7.opengw.net 443 tcp-client"
        )
        val profile = OpenVPNProfileParser.parse(multiRemote, "public-vpn-7", "8.8.8.8")
        assertEquals(443, profile.serverPort)
    }

    @Test
    fun supportsUdpProfilesAndBuildsUdpEndpointConfig() {
        val profile = OpenVPNProfileParser.parse(
            validProfile.replace("tcp-client", "udp"),
            "public-vpn-7",
            "8.8.8.8"
        )
        assertEquals("udp", profile.network)
        assertTrue(profile.toSingBoxConfig().contains("\"network\":\"udp\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExternalCaFileReferences() {
        OpenVPNProfileParser.parse(
            "client\ndev tun\nproto tcp-client\nremote public-vpn-7.opengw.net 443\nca ca.crt",
            "public-vpn-7",
            "8.8.8.8"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExternalAuthFileReferences() {
        OpenVPNProfileParser.parse(
            validProfile.replace("auth-user-pass", "auth-user-pass credentials.txt"),
            "public-vpn-7",
            "8.8.8.8"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedRemoteHost() {
        OpenVPNProfileParser.parse(
            validProfile.replace("public-vpn-7.opengw.net", "attacker.example"),
            "public-vpn-7",
            "8.8.8.8"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCompressionThatCanEnableVulnerableCompression() {
        OpenVPNProfileParser.parse(
            validProfile + "\ncomp-lzo yes",
            "public-vpn-7",
            "8.8.8.8"
        )
    }
}

class VPNGateClientParsingTest {
    @Test
    fun parsesOfficialCsvAndSortsByQualityBeforeProbe() {
        val profile = """
            client
            dev tun
            proto tcp-client
            remote public-vpn-7.opengw.net 443
            auth-user-pass
            remote-cert-tls server
            <ca>
            -----BEGIN CERTIFICATE-----
            TEST-CA
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(profile.toByteArray())
        val csv = "*vpn_servers\n" +
            "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,OpenVPN_ConfigData_Base64\n" +
            "public-vpn-7,8.8.8.8,1000,20,500000,\"Japan, East\",JP,10,$encoded\n"
        val servers = VPNGateClient().parseCandidates(csv)
        assertEquals(1, servers.size)
        assertEquals("Japan, East", servers.first().countryName)
        assertEquals("8.8.8.8", servers.first().endpointIp)
        assertEquals("public-vpn-7.opengw.net", servers.first().profile.server)
        assertEquals("TCP", servers.first().transport)
        assertTrue(servers.first().probeLatencyMs == null)
    }

    @Test
    fun keepsUdpCandidatesForFullTunnelVerification() {
        val profile = """
            client
            dev tun
            proto udp
            remote vpn-udp.opengw.net 1194
            auth-user-pass
            <ca>
            -----BEGIN CERTIFICATE-----
            TEST-CA
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(profile.toByteArray())
        val csv = "*vpn_servers\n" +
            "#HostName,IP,Score,Ping,CountryLong,CountryShort,NumVpnSessions,OpenVPN_ConfigData_Base64\n" +
            "vpn-udp,8.8.8.8,1000,10,Japan,JP,10,$encoded\n"
        val servers = VPNGateClient().parseCandidates(csv)
        assertEquals(1, servers.size)
        assertEquals("UDP", servers.first().transport)
        assertEquals(null, servers.first().probeLatencyMs)
    }

    @Test
    fun rejectsPrivateAddressRecords() {
        val profile = """
            client
            dev tun
            proto tcp-client
            remote private-vpn.opengw.net 443
            auth-user-pass
            <ca>
            -----BEGIN CERTIFICATE-----
            TEST-CA
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(profile.toByteArray())
        val csv = "*vpn_servers\n" +
            "#HostName,IP,Score,Ping,CountryLong,CountryShort,NumVpnSessions,OpenVPN_ConfigData_Base64\n" +
            "private-vpn,192.168.1.20,100,20,Japan,JP,10,$encoded\n"
        assertTrue(VPNGateClient().parseCandidates(csv).isEmpty())
    }
}
