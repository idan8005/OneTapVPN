# Raahat VPN

Raahat VPN is a clean, modular, hybrid VPN product for Android, Windows and iOS.

**Product model:** Free public discovery + managed Premium infrastructure.  
**VPN engines:** sing-box and OpenVPN integration points. WireGuard is intentionally excluded.

## Repository layout
- backend/ — Go control-plane API and server catalogue.
- clients/android/ — native Kotlin / Jetpack Compose client.
- clients/windows/ — native WPF client.
- clients/ios/ — Swift core package, SwiftUI app sources and Packet Tunnel extension sources.
- contracts/ — versioned HTTP API contract.
- docs/ — architecture decisions and honest implementation status.

## Local backend
Requirements: Go 1.23 or newer.

From the backend directory run:
- go test ./...
- go run ./cmd/api

The API listens on 127.0.0.1:8080 by default:
- GET /healthz
- GET /api/v1/health
- GET /api/v1/servers

The sample catalogue is intentionally empty. No public or Premium server is invented or bundled. Set RAAHAT_CATALOG_FILE to a validated JSON catalogue file to load server metadata.

For remote deployment, configure TLS directly with RAAHAT_TLS_CERT_FILE and RAAHAT_TLS_KEY_FILE, or place the service behind a trusted TLS-terminating proxy and explicitly enable the documented proxy deployment mode. Do not expose plain HTTP to the public internet.

## Build and test
- Backend: cd backend && go test -race ./... && go vet ./...
- Android: open clients/android in Android Studio, or run Gradle tasks from that directory.
- Windows: build clients/windows/RaahatVPN.csproj on Windows with the .NET 10 SDK.
- iOS core: swift test --package-path clients/ios.
- iOS app and Packet Tunnel: generate the Xcode project using XcodeGen on macOS, then sign with an Apple Developer profile that has the Network Extension entitlement.

## Important implementation boundary
This clean foundation deliberately does not claim that a VPN tunnel is already operational. The native clients display a disconnected state and keep connection controls disabled until a real engine adapter is integrated and tested on devices. The server catalogue API is a foundation; public-source ingestion, server probes, authenticated Premium leases, payments, production database storage and a production admin console are follow-up implementation stages.

See docs/STATUS.md and docs/ARCHITECTURE.md.
