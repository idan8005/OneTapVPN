# Implementation status

Updated: 2026-11-22

## Android engine integration — development milestone 0.2.0-dev
- Added the official sing-box Android libbox binding (1.14.4) as the Android runtime engine.
- Added an Android VpnService foreground tunnel host and TUN descriptor lifecycle.
- Added socket protection so engine sockets do not loop back into the VPN.
- Added Android default-network monitoring and interface metadata callbacks.
- Added a VLESS + REALITY TCP share-link parser and sing-box configuration generator.
- Added libbox configuration validation before starting a tunnel.
- Added real HTTPS traffic verification before the app reports Connected.
- Added ABI split APK builds for arm64-v8a, armeabi-v7a, x86_64 and x86.
- Connection stays failed/disconnected when TUN setup or external traffic verification fails.

## Not implemented yet
- Automatic discovery and active health testing of public/free servers.
- A curated server catalogue with working, permission-cleared endpoints.
- OpenVPN client engine integration.
- Secure at-rest persistence for imported connection profiles.
- Premium account authentication, short-lived connection leases, payments and capacity management.
- PostgreSQL/Redis persistence and production admin panel.
- Windows tunnel/engine integration.
- iOS sing-box Packet Tunnel integration and Apple signing/provisioning.
- Signed in-app update service.
- Real-device verification for DNS leak, IPv6, kill switch, split tunneling, Wi-Fi/mobile handover and battery use.

## Validation and release rule
CI compilation and unit tests are necessary but not proof of a working VPN connection. A valid VLESS + REALITY server endpoint is required for end-to-end connection testing. Do not publish this development build as a production-ready VPN until physical-device testing and a security review pass.
