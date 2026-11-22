import Foundation
import NetworkExtension
import RaahatCore
import SwiftUI

@MainActor
final class VPNConnectionModel: ObservableObject {
    @Published var serverLink = ""
    @Published private(set) var status = "قطع است"
    @Published private(set) var detail = "لینک سرور VLESS + REALITY را وارد کنید."
    @Published private(set) var isConnecting = false
    @Published private(set) var isConnected = false
    @Published private(set) var isVerified = false
    @Published private(set) var exitAddress = ""

    private var manager: NETunnelProviderManager?

    func restoreState() {
        Task {
            do {
                let managers = try await NETunnelProviderManager.loadAllFromPreferences()
                manager = managers.first(where: Self.isRaahatManager)
                updateFromSystemStatus()
            } catch {
                detail = "وضعیت ذخیره‌شدهٔ VPN در iOS در دسترس نیست."
            }
        }
    }

    func refreshSystemStatus() {
        updateFromSystemStatus()
    }

    func connect() {
        guard !isConnecting else { return }
        Task { await connectNow() }
    }

    func disconnect() {
        guard let session = manager?.connection else {
            status = "قطع است"
            isConnected = false
            isVerified = false
            return
        }

        session.stopVPNTunnel()
        status = "در حال قطع اتصال..."
        detail = "درخواست قطع اتصال به iOS ارسال شد."
        isConnected = false
        isVerified = false
        exitAddress = ""
    }

    private func connectNow() async {
        isConnecting = true
        isVerified = false
        exitAddress = ""
        status = "در حال آماده‌سازی..."
        detail = "لینک بررسی و پیکربندی تونل ساخته می‌شود."

        defer { isConnecting = false }

        do {
            let profile = try VlessRealityProfile.parse(serverLink)
            let configurationData = try profile.makeSingBoxConfiguration()
            guard let configuration = String(data: configurationData, encoding: .utf8) else {
                throw VlessRealityError.configurationEncodingFailed
            }

            // This baseline is used only for a temporary before/after IPv4 egress comparison.
            // api.ipify.org can observe the public source IP of each request.
            let baselineAddress = try? await fetchPublicIPv4()
            let activeManager = try await prepareManager()
            guard let session = activeManager.connection as? NETunnelProviderSession else {
                throw ConnectionError.providerSessionUnavailable
            }

            try session.startVPNTunnel(options: [
                "configContent": configuration as NSString
            ])

            manager = activeManager
            status = "درخواست اتصال ارسال شد"
            detail = "منتظر تأیید و راه‌اندازی افزونهٔ VPN توسط iOS هستیم."

            let connected = await waitForConnectedState(session, seconds: 30)
            guard connected else {
                updateFromSystemStatus()
                if session.status != .connected {
                    status = "اتصال هنوز تأیید نشده"
                    detail = "iOS هنوز وضعیت اتصال را تأیید نکرده است. وضعیت VPN را بررسی کنید."
                }
                return
            }

            isConnected = true
            status = "تونل فعال است؛ در حال بررسی خروجی"

            do {
                let tunnelAddress = try await fetchPublicIPv4()
                exitAddress = tunnelAddress
                if let baselineAddress, tunnelAddress != baselineAddress {
                    isVerified = true
                    status = "خروجی VPN تأیید شد"
                    detail = "آدرس خروجی IPv4 پس از اتصال تغییر کرده است."
                } else {
                    isVerified = false
                    status = "تونل فعال؛ خروجی تأییدنشده"
                    detail = "IP خروجی با IP پیش از اتصال تفاوت نداشت یا IP قبل از اتصال در دسترس نبود. این اتصال را سالم فرض نکنید."
                }
            } catch {
                isVerified = false
                status = "تونل فعال؛ بررسی خروجی ناموفق"
                detail = "iOS اتصال را فعال نشان می‌دهد، اما بررسی مستقل HTTPS انجام نشد."
            }
        } catch let error as VlessRealityError {
            status = "لینک سرور معتبر نیست"
            detail = "لینک باید VLESS + REALITY با انتقال TCP و پارامترهای معتبر داشته باشد."
        } catch ConnectionError.providerSessionUnavailable {
            status = "راه‌اندازی VPN ناموفق"
            detail = "iOS افزونهٔ Packet Tunnel را در تنظیمات VPN پیدا نکرد."
        } catch {
            status = "اتصال برقرار نشد"
            detail = "iOS نتوانست VPN را راه‌اندازی کند. مجوز VPN و لینک سرور را بررسی کنید."
        }
    }

    private func prepareManager() async throws -> NETunnelProviderManager {
        let managers = try await NETunnelProviderManager.loadAllFromPreferences()
        let activeManager = managers.first(where: Self.isRaahatManager) ?? NETunnelProviderManager()

        let tunnelProtocol = NETunnelProviderProtocol()
        tunnelProtocol.providerBundleIdentifier = "com.raahat.vpn.packet-tunnel"
        tunnelProtocol.serverAddress = "Raahat VPN"
        // Credentials stay out of the saved VPN preference payload. The full config is passed
        // only to startVPNTunnel and then stored by the extension in its protected App Group.
        tunnelProtocol.providerConfiguration = ["configVersion": 1]

        activeManager.protocolConfiguration = tunnelProtocol
        activeManager.localizedDescription = "Raahat VPN"
        activeManager.isEnabled = true
        activeManager.isOnDemandEnabled = false

        try await activeManager.saveToPreferences()
        try await activeManager.loadFromPreferences()
        return activeManager
    }

    private static func isRaahatManager(_ manager: NETunnelProviderManager) -> Bool {
        guard let tunnelProtocol = manager.protocolConfiguration as? NETunnelProviderProtocol else {
            return false
        }
        return tunnelProtocol.providerBundleIdentifier == "com.raahat.vpn.packet-tunnel"
    }

    private func waitForConnectedState(
        _ session: NETunnelProviderSession,
        seconds: Int
    ) async -> Bool {
        for _ in 0..<(seconds * 2) {
            if session.status == .connected {
                return true
            }
            if session.status == .disconnected || session.status == .invalid {
                return false
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        return session.status == .connected
    }

    private func updateFromSystemStatus() {
        guard let manager else {
            status = "قطع است"
            isConnected = false
            isVerified = false
            return
        }

        switch manager.connection.status {
        case .connected:
            isConnected = true
            isVerified = false
            status = "اتصال VPN فعال است"
            detail = "وضعیت سیستم فعال است؛ خروجی اینترنت در این نوبت بررسی نشده."
        case .connecting, .reasserting:
            isConnecting = true
            isConnected = false
            status = "در حال اتصال..."
            detail = "iOS در حال راه‌اندازی تونل است."
        case .disconnecting:
            isConnecting = false
            isConnected = false
            status = "در حال قطع اتصال..."
            detail = "درخواست قطع اتصال در حال اجراست."
        case .disconnected, .invalid:
            isConnecting = false
            isConnected = false
            isVerified = false
            status = "قطع است"
            detail = "VPN فعال نیست."
        @unknown default:
            isConnecting = false
            isConnected = false
            isVerified = false
            status = "وضعیت نامشخص"
            detail = "iOS وضعیت شناخته‌شده‌ای برای VPN برنگرداند."
        }
    }

    private func fetchPublicIPv4() async throws -> String {
        guard let url = URL(string: "https://api.ipify.org") else {
            throw ConnectionError.invalidEgressService
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse, response.statusCode == 200,
              let value = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
              Self.isPublicIPv4(value)
        else {
            throw ConnectionError.invalidEgressResponse
        }
        return value
    }

    private static func isPublicIPv4(_ value: String) -> Bool {
        let octets = value.split(separator: ".")
        guard octets.count == 4 else { return false }
        let numbers = octets.compactMap { UInt8($0) }
        guard numbers.count == 4 else { return false }
        let first = numbers[0]
        let second = numbers[1]
        let third = numbers[2]

        if first == 0 || first == 10 || first == 127 || first >= 224 { return false }
        if first == 100 && (64...127).contains(Int(second)) { return false }
        if first == 169 && second == 254 { return false }
        if first == 172 && (16...31).contains(Int(second)) { return false }
        if first == 192 && second == 168 { return false }
        if first == 192 && second == 0 && third == 2 { return false }
        if first == 198 && (second == 18 || second == 19) { return false }
        if first == 198 && second == 51 && third == 100 { return false }
        if first == 203 && second == 0 && third == 113 { return false }
        return true
    }

    private enum ConnectionError: Error {
        case providerSessionUnavailable
        case invalidEgressService
        case invalidEgressResponse
    }
}

struct ContentView: View {
    @StateObject private var model = VPNConnectionModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    VStack(spacing: 8) {
                        Image(systemName: "shield.lefthalf.filled")
                            .font(.system(size: 58))
                            .foregroundStyle(.blue)
                        Text("راحت وی‌پی‌ان")
                            .font(.largeTitle.bold())
                        Text("اتصال ساده با سرور VLESS + REALITY")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.top, 24)

                    VStack(spacing: 10) {
                        Text(model.status)
                            .font(.title3.bold())
                            .multilineTextAlignment(.center)
                        Text(model.detail)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        if !model.exitAddress.isEmpty {
                            Text("IP خروجی: \(model.exitAddress)")
                                .font(.caption.monospaced())
                                .foregroundStyle(model.isVerified ? .green : .secondary)
                                .textSelection(.enabled)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(18)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))

                    VStack(alignment: .leading, spacing: 10) {
                        Text("لینک سرور")
                            .font(.headline)
                        Text("لینک کامل VLESS + REALITY با انتقال TCP را اینجا وارد کنید.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextEditor(text: $model.serverLink)
                            .font(.system(.footnote, design: .monospaced))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .environment(\.layoutDirection, .leftToRight)
                            .frame(minHeight: 115)
                            .padding(8)
                            .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    HStack(spacing: 12) {
                        Button {
                            model.connect()
                        } label: {
                            Label("اتصال", systemImage: "power")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.isConnecting || model.isConnected || model.serverLink.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                        Button {
                            model.disconnect()
                        } label: {
                            Label("قطع", systemImage: "stop.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .disabled(!model.isConnected && !model.isConnecting)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Label("محدودیت‌های نسخهٔ آزمایشی", systemImage: "info.circle")
                            .font(.headline)
                        Text("این نسخه فقط VLESS + REALITY روی TCP را می‌پذیرد. بررسی فعلی، تفاوت IP عمومی IPv4 قبل و بعد از اتصال است؛ آزمون مستقل نشت DNS و IPv6 و Kill Switch هنوز تکمیل نشده است.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Text("برای مقایسهٔ IP، یک درخواست HTTPS به api.ipify.org ارسال می‌شود و آن سرویس IP عمومی درخواست را می‌بیند.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))

                    Text("نسخه 0.2.0-dev · sing-box 1.14.4")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .padding(.bottom, 20)
                }
                .padding(.horizontal, 22)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .navigationBarHidden(true)
            .onAppear { model.restoreState() }
            .onReceive(NotificationCenter.default.publisher(for: .NEVPNStatusDidChange)) { _ in
                model.refreshSystemStatus()
            }
        }
        .environment(\.layoutDirection, .rightToLeft)
    }
}
