import Foundation
import XCTest
@testable import RaahatCore

final class RaahatCoreTests: XCTestCase {
    private let validVlessLink =
        "vless://11111111-1111-4111-8111-111111111111@example.com:443" +
        "?security=reality&type=tcp" +
        "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "&sid=0123456789abcdef&sni=example.com&fp=chrome#Test%20Relay"

    func testServerListDecoding() throws {
        let json = """
        {
          "data": [{
            "id": "de-1",
            "name": "Germany 1",
            "country_code": "DE",
            "region": "Frankfurt",
            "tier": "free",
            "protocol": "openvpn",
            "healthy": true,
            "latency_ms": 44,
            "packet_loss_percent": 0.0,
            "capacity_percent": 30.0
          }],
          "count": 1
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let response = try decoder.decode(ServerListResponse.self, from: json)
        XCTAssertEqual(response.count, 1)
        XCTAssertEqual(response.data.first?.id, "de-1")
        XCTAssertEqual(response.data.first?.tier, .free)
    }

    func testCatalogRequiresHTTPS() {
        XCTAssertThrowsError(try ServerCatalogClient(baseURL: URL(string: "http://localhost:8080")!))
    }

    func testVlessRealityProfileParsing() throws {
        let profile = try VlessRealityProfile.parse(validVlessLink)
        XCTAssertEqual(profile.name, "Test Relay")
        XCTAssertEqual(profile.server, "example.com")
        XCTAssertEqual(profile.serverPort, 443)
        XCTAssertEqual(profile.uuid.uuidString.lowercased(), "11111111-1111-4111-8111-111111111111")
        XCTAssertEqual(profile.serverName, "example.com")
        XCTAssertEqual(profile.shortID, "0123456789abcdef")
        XCTAssertEqual(profile.fingerprint, "chrome")
    }

    func testVlessRealityRejectsUnsupportedOrMalformedLinks() {
        let badScheme = validVlessLink.replacingOccurrences(of: "vless://", with: "https://")
        XCTAssertThrowsError(try VlessRealityProfile.parse(badScheme))

        let noReality = validVlessLink.replacingOccurrences(of: "security=reality", with: "security=none")
        XCTAssertThrowsError(try VlessRealityProfile.parse(noReality))

        let udp = validVlessLink.replacingOccurrences(of: "type=tcp", with: "type=udp")
        XCTAssertThrowsError(try VlessRealityProfile.parse(udp))

        let badKey = validVlessLink.replacingOccurrences(
            of: "pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            with: "pbk=bad"
        )
        XCTAssertThrowsError(try VlessRealityProfile.parse(badKey))

        let duplicate = validVlessLink.replacingOccurrences(
            of: "#Test%20Relay",
            with: "&security=reality#Test%20Relay"
        )
        XCTAssertThrowsError(try VlessRealityProfile.parse(duplicate))
    }

    func testVlessRealityGeneratesFullTunnelAndEncryptedDNSConfiguration() throws {
        let profile = try VlessRealityProfile.parse(validVlessLink)
        let data = try profile.makeSingBoxConfiguration()
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )

        let inbounds = try XCTUnwrap(object["inbounds"] as? [[String: Any]])
        XCTAssertEqual(inbounds.first?["type"] as? String, "tun")
        XCTAssertEqual(inbounds.first?["auto_route"] as? Bool, true)

        let dns = try XCTUnwrap(object["dns"] as? [String: Any])
        let dnsServers = try XCTUnwrap(dns["servers"] as? [[String: Any]])
        XCTAssertEqual(dnsServers.first?["type"] as? String, "https")
        XCTAssertEqual(dnsServers.first?["detour"] as? String, "proxy")

        let route = try XCTUnwrap(object["route"] as? [String: Any])
        XCTAssertEqual(route["final"] as? String, "proxy")

        let outbounds = try XCTUnwrap(object["outbounds"] as? [[String: Any]])
        XCTAssertEqual(outbounds.first?["type"] as? String, "vless")
    }
}
