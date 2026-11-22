import Foundation
import Libbox
import Network
import NetworkExtension

/// Minimal iOS Network Extension adapter for the pinned sing-box 1.14.4 Libbox API.
/// Unsupported host capabilities fail explicitly instead of pretending to be available.
final class RaahatPlatformInterface: NSObject,
    LibboxPlatformInterfaceProtocol,
    LibboxCommandServerHandlerProtocol
{
    private weak var tunnel: PacketTunnelProvider?
    private var networkSettings: NEPacketTunnelNetworkSettings?
    private var pathMonitor: NWPathMonitor?

    init(tunnel: PacketTunnelProvider) {
        self.tunnel = tunnel
        super.init()
    }

    // MARK: - TUN and Network Extension

    func openTun(
        _ options: LibboxTunOptionsProtocol?,
        ret0_: UnsafeMutablePointer<Int32>?
    ) throws {
        guard let options, let ret0_, let tunnel else {
            throw error("Missing TUN options, file descriptor output, or Packet Tunnel provider.")
        }

        try runBlocking {
            try await self.openTunAsync(options, ret0_: ret0_, tunnel: tunnel)
        }
    }

    private func openTunAsync(
        _ options: LibboxTunOptionsProtocol,
        ret0_: UnsafeMutablePointer<Int32>,
        tunnel: PacketTunnelProvider
    ) async throws {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        settings.mtu = NSNumber(value: options.getMTU())

        var dnsServers: [String] = []
        if options.getDNSMode()?.value != LibboxDNSModeDisabled {
            let iterator = try options.getDNSServerAddress()
            while iterator?.hasNext() == true {
                if let address = iterator?.next(), !address.isEmpty {
                    dnsServers.append(address)
                }
            }
        }

        if !dnsServers.isEmpty {
            let dns = NEDNSSettings(servers: dnsServers)
            dns.matchDomains = [""]
            dns.matchDomainsNoSearch = true
            settings.dnsSettings = dns
        }

        var ipv4Addresses: [String] = []
        var ipv4Masks: [String] = []
        let ipv4AddressIterator = options.getInet4Address()
        while ipv4AddressIterator?.hasNext() == true {
            guard let prefix = ipv4AddressIterator?.next() else { break }
            ipv4Addresses.append(prefix.address())
            ipv4Masks.append(prefix.mask())
        }

        if !ipv4Addresses.isEmpty {
            let ipv4 = NEIPv4Settings(addresses: ipv4Addresses, subnetMasks: ipv4Masks)
            var routes: [NEIPv4Route] = []
            let routeIterator = options.getInet4RouteAddress()
            while routeIterator?.hasNext() == true {
                guard let prefix = routeIterator?.next() else { break }
                routes.append(
                    NEIPv4Route(
                        destinationAddress: prefix.address(),
                        subnetMask: prefix.mask()
                    )
                )
            }
            if routes.isEmpty {
                routes.append(NEIPv4Route.default())
            }

            var excluded: [NEIPv4Route] = []
            let excludeIterator = options.getInet4RouteExcludeAddress()
            while excludeIterator?.hasNext() == true {
                guard let prefix = excludeIterator?.next() else { break }
                excluded.append(
                    NEIPv4Route(
                        destinationAddress: prefix.address(),
                        subnetMask: prefix.mask()
                    )
                )
            }
            ipv4.includedRoutes = routes
            ipv4.excludedRoutes = excluded
            settings.ipv4Settings = ipv4
        }

        var ipv6Addresses: [String] = []
        var ipv6Prefixes: [NSNumber] = []
        let ipv6AddressIterator = options.getInet6Address()
        while ipv6AddressIterator?.hasNext() == true {
            guard let prefix = ipv6AddressIterator?.next() else { break }
            ipv6Addresses.append(prefix.address())
            ipv6Prefixes.append(NSNumber(value: prefix.prefix()))
        }

        if !ipv6Addresses.isEmpty {
            let ipv6 = NEIPv6Settings(
                addresses: ipv6Addresses,
                networkPrefixLengths: ipv6Prefixes
            )
            var routes: [NEIPv6Route] = []
            let routeIterator = options.getInet6RouteAddress()
            while routeIterator?.hasNext() == true {
                guard let prefix = routeIterator?.next() else { break }
                routes.append(
                    NEIPv6Route(
                        destinationAddress: prefix.address(),
                        networkPrefixLength: NSNumber(value: prefix.prefix())
                    )
                )
            }
            if routes.isEmpty {
                routes.append(NEIPv6Route.default())
            }

            var excluded: [NEIPv6Route] = []
            let excludeIterator = options.getInet6RouteExcludeAddress()
            while excludeIterator?.hasNext() == true {
                guard let prefix = excludeIterator?.next() else { break }
                excluded.append(
                    NEIPv6Route(
                        destinationAddress: prefix.address(),
                        networkPrefixLength: NSNumber(value: prefix.prefix())
                    )
                )
            }
            ipv6.includedRoutes = routes
            ipv6.excludedRoutes = excluded
            settings.ipv6Settings = ipv6
        }

        networkSettings = settings
        try await tunnel.setTunnelNetworkSettings(settings)

        // Libbox exposes the TUN descriptor through a supported bridge function.
        // Avoid KVC/private access to NEPacketTunnelFlow internals.
        let descriptor = LibboxGetTunnelFileDescriptor()
        guard descriptor >= 0 else {
            throw error("The Packet Tunnel did not provide a valid TUN file descriptor.")
        }
        ret0_.pointee = descriptor
    }

    func usePlatformAutoDetectInterfaceControl() -> Bool { false }

    func autoDetectInterfaceControl(_ fd: Int32) throws {
        // Network Extension manages the physical interface and route protection.
    }

    func underNetworkExtension() -> Bool { true }

    func includeAllNetworks() -> Bool { false }

    func startDefaultInterfaceMonitor(
        _ listener: LibboxInterfaceUpdateListenerProtocol?
    ) throws {
        guard let listener else { return }
        pathMonitor?.cancel()
        let monitor = NWPathMonitor()
        pathMonitor = monitor
        monitor.pathUpdateHandler = { path in
            let pathDescription = Self.describe(path)
            listener.updateNetworkPath(pathDescription)
            guard path.status == .satisfied,
                  let interface = path.availableInterfaces.first
            else {
                listener.updateDefaultInterface(
                    "",
                    interfaceIndex: -1,
                    isExpensive: false,
                    isConstrained: false
                )
                return
            }
            listener.updateDefaultInterface(
                interface.name,
                interfaceIndex: Int32(interface.index),
                isExpensive: path.isExpensive,
                isConstrained: path.isConstrained
            )
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
    }

    func closeDefaultInterfaceMonitor(
        _ listener: LibboxInterfaceUpdateListenerProtocol?
    ) throws {
        pathMonitor?.cancel()
        pathMonitor = nil
    }

    func getInterfaces() throws -> LibboxNetworkInterfaceIteratorProtocol {
        let interfaces = (pathMonitor?.currentPath.availableInterfaces ?? []).map { item in
            let result = LibboxNetworkInterface()
            result.name = item.name
            result.index = Int32(item.index)
            switch item.type {
            case .wifi:
                result.type = LibboxInterfaceTypeWIFI
            case .cellular:
                result.type = LibboxInterfaceTypeCellular
            case .wiredEthernet:
                result.type = LibboxInterfaceTypeEthernet
            default:
                result.type = LibboxInterfaceTypeOther
            }
            return result
        }
        return NetworkInterfaceIterator(interfaces)
    }

    func readWIFIState() -> LibboxWIFIState? { nil }

    func clearDNSCache() {
        // iOS owns the system DNS cache. The tunnel DNS settings are reapplied on reconnect.
    }

    // MARK: - Connection and optional platform features

    func findConnectionOwner(
        _ ipProtocol: Int32,
        sourceAddress: String?,
        sourcePort: Int32,
        destinationAddress: String?,
        destinationPort: Int32
    ) throws -> LibboxConnectionOwner {
        // Per-process attribution is not exposed to third-party iOS packet tunnels.
        LibboxConnectionOwner()
    }

    func localDNSTransport() -> LibboxLocalDNSTransportProtocol? { nil }

    func sendNotification(_ notification: LibboxNotification?) throws {
        // Notifications are not needed for tunnel operation.
    }

    func cancelNotification(_ identifier: String?, typeID: Int32) throws {}

    func startNeighborMonitor(_ listener: LibboxNeighborUpdateListenerProtocol?) throws {}

    func closeNeighborMonitor(_ listener: LibboxNeighborUpdateListenerProtocol?) throws {}

    func registerMyInterface(_ name: String?) {}

    func usePlatformShell() -> Bool { false }

    func checkPlatformShell() throws {
        throw error("Platform shell access is not available on iOS.")
    }

    func openShellSession(
        _ user: LibboxPlatformUser?,
        command: String?,
        environ: LibboxStringIteratorProtocol?,
        term: String?,
        rows: Int32,
        cols: Int32
    ) throws -> any LibboxShellSessionProtocol {
        throw error("Shell sessions are not available on iOS.")
    }

    func lookupUser(_ username: String?) throws -> LibboxPlatformUser {
        throw error("Operating-system user lookup is not available on iOS.")
    }

    func lookupSFTPServer(_ errorPointer: NSErrorPointer) -> String {
        errorPointer?.pointee = error("SFTP is not available on iOS.")
        return ""
    }

    func readSystemSSHHostKey(_ errorPointer: NSErrorPointer) -> String {
        errorPointer?.pointee = error("System SSH host keys are not available on iOS.")
        return ""
    }

    func tailscaleHostname() -> String { "Raahat-iOS" }

    func usePlatformBridge() -> Bool { false }

    func createBridge(
        _ options: LibboxBridgeOptions?
    ) throws -> any LibboxBridgeSessionProtocol {
        throw error("Platform bridge devices are not available on iOS.")
    }

    func connectSSHAgent(_ ret0_: UnsafeMutablePointer<Int32>?) throws {
        throw error("SSH agent forwarding is not available on iOS.")
    }

    // MARK: - Libbox command server handler

    func serviceStop() throws {
        try tunnel?.stopLibboxService()
    }

    func serviceReload() throws {
        try tunnel?.reloadLibboxService()
    }

    func getSystemProxyStatus() throws -> LibboxSystemProxyStatus {
        LibboxSystemProxyStatus()
    }

    func setSystemProxyEnabled(_ enabled: Bool) throws {
        // Packet Tunnel routing is controlled by Network Extension, not a system HTTP proxy.
    }

    func triggerNativeCrash() throws {
        throw error("Native crash triggering is disabled in release builds.")
    }

    func writeDebugMessage(_ message: String?) {
        // Never persist engine debug messages; they may contain connection metadata.
    }

    // MARK: - Helpers

    private func error(_ message: String) -> NSError {
        NSError(
            domain: "com.raahat.vpn.packet-tunnel",
            code: 1200,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
    }

    private static func describe(_ path: NWPath) -> String {
        let interfaces = path.availableInterfaces
            .map { "\($0.name)#\($0.index)/\($0.type)" }
            .joined(separator: ",")
        return "status=\(path.status) interfaces=\(interfaces) ipv4=\(path.supportsIPv4) ipv6=\(path.supportsIPv6) dns=\(path.supportsDNS)"
    }

    private func runBlocking<T>(
        _ operation: @escaping () async throws -> T
    ) throws -> T {
        let semaphore = DispatchSemaphore(value: 0)
        let result = SynchronousResult<T>()
        Task {
            do {
                result.set(.success(try await operation()))
            } catch {
                result.set(.failure(error))
            }
            semaphore.signal()
        }
        semaphore.wait()
        return try result.get()
    }
}

private final class SynchronousResult<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Result<Value, Error>?

    func set(_ result: Result<Value, Error>) {
        lock.lock()
        stored = result
        lock.unlock()
    }

    func get() throws -> Value {
        lock.lock()
        let result = stored
        lock.unlock()
        guard let result else {
            throw NSError(
                domain: "com.raahat.vpn.packet-tunnel",
                code: 1201,
                userInfo: [NSLocalizedDescriptionKey: "Missing asynchronous result."]
            )
        }
        return try result.get()
    }
}

private final class NetworkInterfaceIterator: NSObject, LibboxNetworkInterfaceIteratorProtocol {
    private var iterator: IndexingIterator<[LibboxNetworkInterface]>
    private var nextValue: LibboxNetworkInterface?

    init(_ interfaces: [LibboxNetworkInterface]) {
        iterator = interfaces.makeIterator()
    }

    func hasNext() -> Bool {
        nextValue = iterator.next()
        return nextValue != nil
    }

    func next() -> LibboxNetworkInterface? {
        nextValue
    }
}
