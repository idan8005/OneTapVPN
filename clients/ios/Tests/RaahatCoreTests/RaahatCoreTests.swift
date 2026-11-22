import Foundation
import XCTest
@testable import RaahatCore

final class RaahatCoreTests: XCTestCase {
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
}
