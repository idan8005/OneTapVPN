import Foundation
import Libbox
import NetworkExtension
import RaahatCore
import Security

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
            deleteConfigurationEncryptionKey()
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

        let encryptedData = try Data(contentsOf: fileURL)
        let data = try ConfigurationCipher.open(
            encryptedData,
            keyData: configurationEncryptionKey()
        )
        guard let configuration = String(data: data, encoding: .utf8), !configuration.isEmpty else {
            throw tunnelError("The saved VPN configuration could not be decrypted or decoded.")
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

        // The extension owns this keychain item; the containing app cannot read the key.
        // AES-GCM authenticates the full encrypted payload before recovery.
        let encryptedData = try ConfigurationCipher.seal(
            data,
            keyData: configurationEncryptionKey()
        )
        try encryptedData.write(
            to: fileURL,
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
    }

    private func configurationEncryptionKey() throws -> Data {
        let service = "com.raahat.vpn.packet-tunnel.configuration"
        let account = "latest-config-aes256-v1"
        let baseQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]

        for attempt in 0..<2 {
            var lookup = baseQuery
            lookup[kSecReturnData as String] = true
            lookup[kSecMatchLimit as String] = kSecMatchLimitOne

            var existing: CFTypeRef?
            let lookupStatus = SecItemCopyMatching(lookup as CFDictionary, &existing)
            if lookupStatus == errSecSuccess {
                guard let keyData = existing as? Data, keyData.count == 32 else {
                    throw tunnelError("The saved encryption key is invalid.")
                }
                return keyData
            }
            guard lookupStatus == errSecItemNotFound else {
                throw keychainError(lookupStatus)
            }

            var keyData = Data(count: 32)
            let randomStatus = keyData.withUnsafeMutableBytes { buffer in
                SecRandomCopyBytes(kSecRandomDefault, 32, buffer.baseAddress!)
            }
            guard randomStatus == errSecSuccess else {
                throw keychainError(randomStatus)
            }

            var insertion = baseQuery
            insertion[kSecValueData as String] = keyData
            insertion[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let insertionStatus = SecItemAdd(insertion as CFDictionary, nil)
            if insertionStatus == errSecSuccess {
                return SymmetricKey(data: keyData)
            }
            if insertionStatus == errSecDuplicateItem && attempt == 0 {
                continue
            }
            throw keychainError(insertionStatus)
        }

        throw tunnelError("The configuration encryption key could not be created.")
    }

    private func deleteConfigurationEncryptionKey() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.raahat.vpn.packet-tunnel.configuration",
            kSecAttrAccount as String: "latest-config-aes256-v1"
        ]
        let status = SecItemDelete(query as CFDictionary)
        // A missing item is expected after a fresh install or already-cleared disconnect.
        guard status == errSecSuccess || status == errSecItemNotFound else { return }
    }

    private func keychainError(_ status: OSStatus) -> NSError {
        NSError(
            domain: NSOSStatusErrorDomain,
            code: Int(status),
            userInfo: [NSLocalizedDescriptionKey: "Secure storage for VPN configuration is unavailable."]
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
