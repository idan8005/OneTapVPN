import Foundation
import NetworkExtension

final class PacketTunnelProvider: NEPacketTunnelProvider {
    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        // Fail closed until a reviewed sing-box/OpenVPN adapter is embedded.
        let error = NSError(
            domain: "com.raahat.vpn",
            code: 1001,
            userInfo: [NSLocalizedDescriptionKey: "VPN engine is not configured in this build."]
        )
        completionHandler(error)
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        completionHandler()
    }
}
