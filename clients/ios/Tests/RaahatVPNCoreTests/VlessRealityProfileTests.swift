import Foundation
import XCTest
@testable import RaahatVPNCore
final class VlessRealityProfileTests:XCTestCase{
 private let link="vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?encryption=none&security=reality&sni=www.example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123abcd&type=tcp#Premium"
 func testBuildsFullTunnelConfig()throws{
  let p=try VlessRealityProfile(link:link),data=try p.makeSingBoxConfig()
  let root=try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
  let ins=try XCTUnwrap(root["inbounds"] as? [[String:Any]])
  let outs=try XCTUnwrap(root["outbounds"] as? [[String:Any]])
  XCTAssertEqual(ins.first?["type"] as? String,"tun");XCTAssertEqual(ins.first?["strict_route"] as? Bool,true)
  XCTAssertEqual(outs.first?["type"] as? String,"vless");XCTAssertEqual(outs.first?["server"] as? String,"example.com")
 }
 func testRejectsWireGuardAndNonReality(){
  XCTAssertThrowsError(try VlessRealityProfile(link:"wireguard://example.com"))
  XCTAssertThrowsError(try VlessRealityProfile(link:link.replacingOccurrences(of:"security=reality",with:"security=none")))
 }
 func testRejectsMissingRealityKey(){
  let bad=link.replacingOccurrences(of:"&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",with:"")
  XCTAssertThrowsError(try VlessRealityProfile(link:bad))
 }
}
