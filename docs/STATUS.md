# Implementation status

Updated: 2026-11-22

## Android engine integration — development milestone 0.2.0-dev
- Added official sing-box Android libbox binding (1.14.4).
- Added Android VpnService foreground tunnel host and TUN descriptor lifecycle.
- Added socket protection and physical default-network monitoring.
- Added VLESS + REALITY TCP share-link parsing and sing-box configuration generation.
- Added libbox configuration validation before starting a tunnel.
- Added HTTPS traffic verification before reporting Connected.
- Added ABI split APK builds for arm64-v8a, armeabi-v7a, x86_64 and x86.
- CI builds and unit tests pass. No live server has been supplied for an end-to-end device connection test.

## Free public server discovery — backend milestone
- Added an opt-in VPN Gate adapter for the official HTTPS CSV API.
- Validates CSV fields, public IPv4 addresses, OpenVPN profile size, matching remote host, port and TCP transport.
- Performs bounded TCP reachability probes and excludes candidates whose configured TCP port cannot be reached.
- Stores no downloaded .ovpn profile contents in the public catalogue or server-list API.
- Separates source-reported ping from actual TCP connect latency.
- Refreshes only VPN Gate-owned Free catalogue rows and preserves managed Premium rows.
- Refuses empty refreshes so a source outage cannot erase the last known-good list.
- The adapter is disabled by default; enable it with RAAHAT_VPNGATE_ENABLED=true after deploying the backend.

## Important limitations
- A TCP-reachable result is not an OpenVPN handshake, user authentication, or proof that VPN traffic passes end-to-end.
- Android does not yet have an OpenVPN engine or integration with the VPN Gate catalogue; public entries are not yet connectable from the Android app.
- Public volunteer relays may be slow, unstable, or log traffic under policies outside Raahat's control. Do not treat them as private infrastructure.
- Premium account authentication, short-lived leases, payments, managed server provisioning and admin UI are not implemented.
- PostgreSQL/Redis persistence is not implemented; the current dynamic catalogue is in-memory and refreshes from the source.
- Windows tunnel/engine integration is not implemented.
- iOS sing-box Packet Tunnel integration and Apple signing/provisioning are not implemented.
- Signed in-app update service is not implemented.
- Physical-device verification for DNS leaks, IPv6, kill switch, split tunneling, Wi-Fi/mobile handover and battery use is outstanding.

## Release rule
CI compilation and unit tests are necessary but are not proof of a working VPN connection. Do not publish this development build as a production-ready VPN until engine integration, production server infrastructure, security review and real-device tests pass.
