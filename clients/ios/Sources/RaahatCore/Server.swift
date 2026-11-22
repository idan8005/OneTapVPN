import Foundation

public enum ServerTier: String, Codable, Sendable {
    case free
    case premium
}

public enum ServerProtocol: String, Codable, Sendable {
    case vlessReality = "vless-reality"
    case openVPN = "openvpn"
}

public struct Server: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let countryCode: String
    public let region: String
    public let tier: ServerTier
    public let `protocol`: ServerProtocol
    public let healthy: Bool
    public let latencyMs: Int
    public let packetLossPercent: Double
    public let capacityPercent: Double

    public init(
        id: String,
        name: String,
        countryCode: String,
        region: String,
        tier: ServerTier,
        transport: ServerProtocol,
        healthy: Bool,
        latencyMs: Int,
        packetLossPercent: Double,
        capacityPercent: Double
    ) {
        self.id = id
        self.name = name
        self.countryCode = countryCode
        self.region = region
        self.tier = tier
        self.`protocol` = transport
        self.healthy = healthy
        self.latencyMs = latencyMs
        self.packetLossPercent = packetLossPercent
        self.capacityPercent = capacityPercent
    }
}

public struct ServerListResponse: Codable, Sendable {
    public let data: [Server]
    public let count: Int
}
