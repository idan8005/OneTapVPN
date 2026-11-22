# Implementation status

Updated: 2026-11-22

## Implemented in the clean-rewrite foundation
- Independent branch based on the repository main branch.
- New clean multi-platform directory structure.
- Go API with health endpoints and a validated, read-only server catalogue.
- Disabled and unhealthy servers are excluded from public results.
- Plain HTTP is restricted to loopback by default; remote deployments require TLS or an explicitly configured trusted TLS proxy.
- Android Compose shell with a truthful disconnected/unavailable-engine state.
- Windows WPF shell with a truthful disconnected/unavailable-engine state.
- Swift core package, catalogue client, unit tests, SwiftUI source and Packet Tunnel extension starting source.
- OpenAPI contract and CI definitions.

## Not implemented yet
- A working sing-box or OpenVPN tunnel adapter on any platform.
- Automatic ingestion from audited free-server sources.
- Active network probes, packet-loss measurements or trustworthy capacity telemetry.
- Premium authentication, short-lived connection leases, account management or payments.
- PostgreSQL/Redis persistence and a production admin panel.
- A production update signing and distribution service.
- Apple signing/provisioning and App Store delivery.
- Real-device VPN, DNS leak, IPv6, kill-switch and split-tunnel verification.

## Release rule
A green compilation or unit-test workflow is not evidence of a working VPN connection. Do not publish this foundation as a usable VPN service until engine integration, server infrastructure, security review and real-device tests have passed.
