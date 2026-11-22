# Implementation status

Updated: 2026-11-22

## Android — milestone 0.3.4-dev
- Official sing-box Android libbox 1.14.4 is used for VLESS + REALITY and OpenVPN Client endpoints.
- Android VpnService owns the foreground tunnel and TUN descriptor lifecycle.
- Engine sockets are protected from VPN routing; physical network monitoring is wired.
- VLESS + REALITY links are parsed and converted to sing-box config.
- VPN Gate's official HTTPS CSV source can be downloaded directly by the Android app.
- OpenVPN .ovpn profiles are parsed in memory. Inline CA/client certificates and tls-auth/tls-crypt keys are handled; unsupported external-file references are rejected.
- Both TCP and UDP OpenVPN profiles are supported by the Android profile parser and sing-box endpoint config.
- TCP profiles are filtered by a bounded TCP connect probe. UDP profiles are not misrepresented as TCP-probed; their actual health is determined by the OpenVPN handshake and HTTPS traffic check.
- Android UI shows source country, protocol and reachability-test details.
- The fastest candidates are tried automatically; after a tunnel or HTTPS verification failure, up to three candidates are attempted before reporting failure.
- Relays that fail a real connection attempt are locally hidden and excluded from automatic selection for a 15-minute cooldown; only a stable server ID and timestamp are persisted.
- The complete sing-box configuration is checked by libbox before starting the tunnel.
- The app checks DNS resolution, IPv4 HTTPS egress and IPv6 HTTPS reachability separately; DNS and public IPv4 are required before reporting Connected, while IPv6 is informational only.
- Android full-tunnel setup ensures IPv4 and IPv6 default routes at the OS layer when the engine supplies only partial routes; Android 13+ uses prefix iterators and older versions use libbox route-range iterators.
- Android VpnService advertises Always-on support. The last tunnel configuration will be encrypted with an Android Keystore AES-GCM key for sticky-service recovery after OS restarts.
- The UI opens Android VPN settings and guides users to enable both Always-on VPN and Block connections without VPN. The app does not automatically toggle this privileged system policy.
- OpenVPN Client endpoint configurations set block_ipv6, which rejects IPv6 locally inside the engine; VLESS routes IPv6 to the proxy outbound.
- Profiles and VPN Gate public credentials remain in memory and are not written to server-list API responses or logs.
- ABI split APK builds target arm64-v8a, armeabi-v7a, x86_64 and x86.

## Backend — VPN Gate ingestion
- Added an opt-in adapter for the official HTTPS CSV API.
- Validates CSV fields, public IPv4 addresses, OpenVPN profile size, matching remote host, port and TCP transport.
- Performs bounded TCP reachability probes and excludes non-responsive candidates.
- Stores no downloaded .ovpn profile contents in the public catalogue or server-list API.
- Separates source-reported ping from measured TCP connect latency.
- Replaces only VPN Gate-owned Free catalogue rows and preserves managed Premium rows.
- Refuses empty refreshes so a source outage cannot erase the last known-good list.
- Backend refresh is opt-in with RAAHAT_VPNGATE_ENABLED=true and runs every 20 minutes. This server-side adapter currently publishes TCP-probed records only.

## Important limitations
- TCP reachability is only a preliminary filter, not an authenticated OpenVPN handshake or proof that VPN traffic passes end-to-end.
- Encrypted configuration recovery and system Always-on / lockdown behavior compile in CI but still require physical-device verification across reboot, service termination, revoked permission and OS vendor variants. Users must enable Android’s Block connections without VPN switch manually.
- Volunteer-run public relays may be slow, unstable or log traffic under policies outside Raahat's control. Do not use them for sensitive traffic.
- Premium authentication, short-lived leases, payments, managed server provisioning and admin UI are not implemented.
- PostgreSQL/Redis persistence is not implemented; the dynamic Backend catalogue is in-memory.
- Windows tunnel/engine integration is not implemented.
- iOS sing-box Packet Tunnel integration and Apple signing/provisioning are not implemented.
- Signed in-app update service is not implemented.
- Physical-device verification for DNS leaks, IPv6 handling, kill switch, split tunneling, Wi-Fi/mobile handover and battery use remains outstanding.

## Release rule
CI compilation and unit tests are necessary but are not proof of a working VPN connection. Do not publish this development build as a production-ready VPN until live server tests, independent security review and real-device testing pass.
