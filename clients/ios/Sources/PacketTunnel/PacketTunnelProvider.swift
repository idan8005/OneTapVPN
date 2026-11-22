import Foundation
import Libbox
import NetworkExtension

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let appGroupIdentifier = "group.com.raahat.vpn"
    private let configurationFileName = "last-known-sing-box-config.json"

    private var commandServer: LibboxCommandServer?
    private var platformInterface: RaahatPlatformInterface?
    private var activeConfiguration: String?

    override init() {
        LibboxPrepareCrashSignalHandlers()
        LibboxReinstallCrashSignalHandlers()
        super.init()
    }

    override func startTunnel(options startOptions: [String: NSObject]?) async throws {
        let configuration = try resolveConfiguration(startOptions)
        guard configuration.utf8.count <= 1_000_000 else {
            throw tunnelError("The saved VPN configuration is too large.")
        }

        var validationError: NSError?
        guard LibboxCheckConfig(configuration, &validationError) else {
            throw validationError ?? tunnelError("sing-box rejected the VPN configuration.")
        }

        let directories = try prepareDirectories()
        try persistConfiguration(configuration, at: directories.base)

        let setupOptions = LibboxSetupOptions()
        setupOptions.basePath = directories.base.path
        setupOptions.workingPath = directories.working.path
        setupOptions.tempPath = directories.temporary.path
        setupOptions.logMaxLines = 2000
        setupOptions.debug = false
        setupOptions.crashReportSource = "RaahatPacketTunnel"
        setupOptions.appVersion = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        setupOptions.appMarketingVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.2.0"
        setupOptions.platformMetadata = #"{"networkExtension":{"provider":"packet-tunnel","includeAllNetworks":false}}"#
        setupOptions.oomKillerEnabled = true
        setupOptions.oomKillerDisabled = false

        var setupError: NSError?
        guard LibboxSetup(setupOptions, &setupError) else {
            throw setupError ?? tunnelError("sing-box could not initialize its runtime.")
        }

        let platform = RaahatPlatformInterface(tunnel: self)
        var serverError: NSError?
        guard let server = LibboxNewCommandServer(platform, platform, &serverError) else {
            throw serverError ?? tunnelError("sing-box could not create its command server.")
        }

        do {
            try server.start()
            try server.startOrReloadService(configuration, options: LibboxOverrideOptions())
        } catch {
            try? server.closeService()
            server.close()
            throw error
        }

        platformInterface = platform
        commandServer = server
        activeConfiguration = configuration
    }

    override func stopTunnel(with reason: NEProviderStopReason) async {
        if let commandServer {
            try? commandServer.closeService()
            commandServer.close()
        }
        commandServer = nil
        platformInterface = nil
        activeConfiguration = nil

        // A user-requested disconnect should not leave a stale configuration for recovery.
        // System/network interruptions preserve it so iOS can restart the Packet Tunnel.
        if reason == .userInitiated {
            if let fileURL = try? configurationURL() {
                try? FileManager.default.removeItem(at: fileURL)
            }
        }
    }

    func stopLibboxService() throws {
        try commandServer?.closeService()
    }

    func reloadLibboxService() throws {
        guard let commandServer, let activeConfiguration else {
            throw tunnelError("There is no saved VPN configuration to reload.")
        }
        try commandServer.startOrReloadService(
            activeConfiguration,
            options: LibboxOverrideOptions()
        )
    }

    private func resolveConfiguration(_ options: [String: NSObject]?) throws -> String {
        if let raw = options?["configContent"] as? String, !raw.isEmpty {
            return raw
        }

        let fileURL = try configurationURL()
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            throw tunnelError("No saved VPN configuration is available. Open Raahat VPN and connect again.")
        }

        let data = try Data(contentsOf: fileURL)
        guard let configuration = String(data: data, encoding: .utf8), !configuration.isEmpty else {
            throw tunnelError("The saved VPN configuration could not be read.")
        }
        return configuration
    }

    private func prepareDirectories() throws -> (base: URL, working: URL, temporary: URL) {
        guard let group = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupIdentifier
        ) else {
            throw tunnelError("The Apple App Group is unavailable. The app and Packet Tunnel require matching App Group provisioning.")
        }

        let base = group.appendingPathComponent("RaahatVPN", isDirectory: true)
        let working = base.appendingPathComponent("Working", isDirectory: true)
        let temporary = base.appendingPathComponent("Temporary", isDirectory: true)

        for directory in [base, working, temporary] {
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
            )
        }
        return (base, working, temporary)
    }

    private func configurationURL() throws -> URL {
        guard let group = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupIdentifier
        ) else {
            throw tunnelError("The Apple App Group is unavailable.")
        }
        return group
            .appendingPathComponent("RaahatVPN", isDirectory: true)
            .appendingPathComponent(configurationFileName, isDirectory: false)
    }

    private func persistConfiguration(_ configuration: String, at base: URL) throws {
        let fileURL = base.appendingPathComponent(configurationFileName, isDirectory: false)
        guard let data = configuration.data(using: .utf8) else {
            throw tunnelError("The VPN configuration could not be encoded.")
        }
        try data.write(
            to: fileURL,
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
    }

    private func tunnelError(_ message: String) -> NSError {
        NSError(
            domain: "com.raahat.vpn.packet-tunnel",
            code: 1300,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
    }
}
