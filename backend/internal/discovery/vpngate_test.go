package discovery

import (
	"encoding/base64"
	"fmt"
	"net"
	"strings"
	"testing"
)

func vpnGateCSV(profile, ip string) []byte {
	encoded := base64.StdEncoding.EncodeToString([]byte(profile))
	return []byte(fmt.Sprintf(
		"*vpn_servers\n#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\npublic-vpn-85,%s,2897411,13,534347694,Japan,JP,159,2509041469,13850243,825383845971680,2weeks,operator,message,%s\n",
		ip, encoded,
	))
}

func TestParseVPNGateCSVDecodesAndValidatesTCPProfile(t *testing.T) {
	profile := "client\ndev tun\nproto tcp-client\nremote public-vpn-85.opengw.net 443\n"
	servers, err := parseVPNGateCSV(vpnGateCSV(profile, "8.8.8.8"), 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(servers) != 1 {
		t.Fatalf("got %d candidates, want 1", len(servers))
	}
	server := servers[0].server
	if server.Protocol != "openvpn" || server.Tier != "free" {
		t.Fatalf("unexpected type: %+v", server)
	}
	if server.EndpointHost != "public-vpn-85.opengw.net" || server.EndpointPort != 443 {
		t.Fatalf("unexpected endpoint: %s:%d", server.EndpointHost, server.EndpointPort)
	}
	if server.Healthy {
		t.Fatal("candidate must not be marked healthy before a TCP probe")
	}
	if server.Source != "vpn-gate" || server.VerificationLevel != "" {
		t.Fatalf("unexpected source verification state: %+v", server)
	}
}

func TestParseVPNGateCSVAcceptsRemoteBeforeProto(t *testing.T) {
	profile := "client\nremote public-vpn-85.opengw.net 443\nproto tcp-client\n"
	servers, err := parseVPNGateCSV(vpnGateCSV(profile, "8.8.8.8"), 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(servers) != 1 {
		t.Fatalf("got %d candidates, want 1", len(servers))
	}
}

func TestParseVPNGateCSVRejectsUDPOnlyProfiles(t *testing.T) {
	profile := "client\ndev tun\nproto udp\nremote public-vpn-85.opengw.net 1194\n"
	servers, err := parseVPNGateCSV(vpnGateCSV(profile, "8.8.8.8"), 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(servers) != 0 {
		t.Fatalf("got %d UDP candidates, want 0", len(servers))
	}
}

func TestParseVPNGateCSVRejectsPrivateIPAndMismatchedProfileHost(t *testing.T) {
	profile := "client\nproto tcp-client\nremote public-vpn-85.opengw.net 443\n"
	servers, err := parseVPNGateCSV(vpnGateCSV(profile, "192.168.1.10"), 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(servers) != 0 {
		t.Fatal("private server address must be rejected")
	}

	mismatch := strings.Replace(profile, "public-vpn-85.opengw.net", "attacker.example", 1)
	servers, err = parseVPNGateCSV(vpnGateCSV(mismatch, "8.8.8.8"), 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(servers) != 0 {
		t.Fatal("profile endpoint not matching the source record must be rejected")
	}
}

func TestParseOpenVPNRemoteRejectsUnknownProtocol(t *testing.T) {
	_, _, protocol, err := parseOpenVPNRemote(
		"client\nproto sctp\nremote public-vpn-85.opengw.net 443\n",
		"public-vpn-85",
		"8.8.8.8",
	)
	if err == nil || protocol != "" {
		t.Fatalf("expected unsupported remote to be rejected, got protocol=%q err=%v", protocol, err)
	}
}

func TestValidPublicIPv4RejectsSpecialRanges(t *testing.T) {
	for _, value := range []string{
		"127.0.0.1", "10.0.0.1", "192.0.2.10", "100.64.0.1", "224.0.0.1",
	} {
		if validPublicIPv4(net.ParseIP(value)) {
			t.Errorf("%s unexpectedly accepted", value)
		}
	}
	if !validPublicIPv4(net.ParseIP("8.8.8.8")) {
		t.Fatal("public IPv4 address unexpectedly rejected")
	}
}
