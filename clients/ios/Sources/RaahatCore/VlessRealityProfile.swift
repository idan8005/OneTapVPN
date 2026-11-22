import Foundation

public struct VlessRealityProfile: Equatable, Sendable {
    public let name: String
    public let server: String
    public let serverPort: Int
    public let uuid: UUID
    public let serverName: String
    public let publicKey: String
    public let shortID: String
    public let fingerprint: String
    public let flow: String

    public static func parse(_ link: String) throws -> VlessRealityProfile {
        guard !link.isEmpty, link.utf8.count <= 8_192,
              let components = URLComponents(string: link.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "vless",
              let host = components.host, !host.isEmpty,
              let port = components.port, (1...65_535).contains(port),
              let user = components.user, let uuid = UUID(uuidString: user)
        else {
            throw VlessRealityError.invalidLink
        }

        let items = components.queryItems ?? []
        var query: [String: String] = [:]
        for item in items {
            let key = item.name.lowercased()
            guard !key.isEmpty, query[key] == nil else {
                throw VlessRealityError.duplicateQueryParameter
            }
            query[key] = item.value ?? ""
        }

        guard query["security"]?.lowercased() == "reality" else {
            throw VlessRealityError.realityRequired
        }

        let transport = query["type"] ?? query["network"] ?? "tcp"
        guard transport.lowercased() == "tcp" else {
            throw VlessRealityError.unsupportedTransport
        }

        let publicKey = query["pbk"] ?? query["publickey"] ?? ""
        guard isValidPublicKey(publicKey) else {
            throw VlessRealityError.invalidRealityPublicKey
        }

        let shortID = query["sid"] ?? query["shortid"] ?? ""
        guard shortID.count <= 16,
              shortID.count.isMultiple(of: 2),
              shortID.allSatisfy({ $0.isHexDigit })
        else {
            throw VlessRealityError.invalidRealityShortID
        }

        let serverName = query["sni"] ?? query["servername"] ?? host
        guard isValidServerName(serverName) else {
            throw VlessRealityError.invalidServerName
        }

        let fingerprint = query["fp"].flatMap { $0.isEmpty ? nil : $0 } ?? "chrome"
        guard fingerprint.count <= 32,
              fingerprint.unicodeScalars.allSatisfy({
                  CharacterSet.alphanumerics.contains($0) || $0 == "_" || $0 == "-"
              })
        else {
            throw VlessRealityError.invalidFingerprint
        }

        let flow = query["flow"] ?? ""
        guard flow.isEmpty || flow == "xtls-rprx-vision" else {
            throw VlessRealityError.unsupportedFlow
        }

        let decodedName = components.fragment ?? "\(host):\(port)"
        let displayName = decodedName.isEmpty ? "\(host):\(port)" : String(decodedName.prefix(80))

        return VlessRealityProfile(
            name: displayName,
            server: host,
            serverPort: port,
            uuid: uuid,
            serverName: serverName,
            publicKey: publicKey,
            shortID: shortID,
            fingerprint: fingerprint,
            flow: flow
        )
    }

    public func makeSingBoxConfiguration() throws -> Data {
        var vless: [String: Any] = [
            "type": "vless",
            "tag": "proxy",
            "server": server,
            "server_port": serverPort,
            "uuid": uuid.uuidString.lowercased(),
            "tls": [
                "enabled": true,
                "server_name": serverName,
                "utls": [
                    "enabled": true,
                    "fingerprint": fingerprint
                ],
                "reality": [
                    "enabled": true,
                    "public_key": publicKey,
                    "short_id": shortID
                ]
            ]
        ]
        if !flow.isEmpty {
            vless["flow"] = flow
        }

        let configuration: [String: Any] = [
            "log": [
                "level": "warn",
                "timestamp": true
            ],
            "dns": [
                "servers": [[
                    "type": "https",
                    "tag": "raahat-doh",
                    "server": "1.1.1.1",
                    "server_port": 443,
                    "path": "/dns-query",
                    "tls": [
                        "enabled": true,
                        "server_name": "cloudflare-dns.com"
                    ],
                    "detour": "proxy"
                ]],
                "final": "raahat-doh",
                "strategy": "prefer_ipv4"
            ],
            "inbounds": [[
                "type": "tun",
                "tag": "raahat-tun",
                "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
                "mtu": 1400,
                "auto_route": true,
                "dns_mode": "native",
                "route_exclude_address": [
                    "10.0.0.0/8",
                    "100.64.0.0/10",
                    "127.0.0.0/8",
                    "169.254.0.0/16",
                    "172.16.0.0/12",
                    "192.168.0.0/16",
                    "::1/128",
                    "fc00::/7",
                    "fe80::/10"
                ]
            ]],
            "outbounds": [
                vless,
                ["type": "direct", "tag": "direct"],
                ["type": "block", "tag": "block"]
            ],
            "route": [
                "auto_detect_interface": true,
                "final": "proxy"
            ]
        ]

        guard JSONSerialization.isValidJSONObject(configuration) else {
            throw VlessRealityError.configurationEncodingFailed
        }
        return try JSONSerialization.data(
            withJSONObject: configuration,
            options: [.sortedKeys]
        )
    }

    private static func isValidPublicKey(_ value: String) -> Bool {
        guard (40...64).contains(value.count),
              value.unicodeScalars.allSatisfy({
                  CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_"
              })
        else {
            return false
        }
        var base64 = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 {
            base64 += String(repeating: "=", count: 4 - remainder)
        }
        return Data(base64Encoded: base64)?.count == 32
    }

    private static func isValidServerName(_ value: String) -> Bool {
        guard !value.isEmpty, value.utf8.count <= 253,
              !value.contains(where: { $0.isWhitespace })
        else {
            return false
        }
        guard let candidate = URLComponents(string: "https://\(value)") else {
            return false
        }
        return candidate.host != nil
    }
}

public enum VlessRealityError: Error, Equatable {
    case invalidLink
    case duplicateQueryParameter
    case realityRequired
    case unsupportedTransport
    case invalidRealityPublicKey
    case invalidRealityShortID
    case invalidServerName
    case invalidFingerprint
    case unsupportedFlow
    case configurationEncodingFailed
}
