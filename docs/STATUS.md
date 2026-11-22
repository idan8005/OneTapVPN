# Implementation status

Updated: 2026-11-22

## Android — milestone 0.3.6-dev
- Android development version: versionCode 9, versionName 0.3.6-dev.
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
- Android VpnService advertises Always-on support. The latest tunnel configuration is encrypted using an Android Keystore AES-GCM key; only the IV and ciphertext are saved in app preferences for sticky-service recovery after OS restarts.
- The UI opens Android VPN settings and guides users to enable both Always-on VPN and Block connections without VPN. The app does not automatically toggle this privileged system policy.
- On Android 10+, the service queries the official VpnService API and the home screen displays the actual Always-on and lockdown values; older Android versions are shown as unsupported/unknown instead of being misreported as disabled.
- A bounded UI refresh timeout handles a missing status response, and unit tests cover enabled, partial, inconsistent and unsupported status combinations.
- OpenVPN Client endpoint configurations set block_ipv6, which rejects IPv6 locally inside the engine; VLESS routes IPv6 to the proxy outbound.
- Profiles and VPN Gate public credentials remain in memory and are not written to server-list API responses or logs.
- ABI split APK builds target arm64-v8a, armeabi-v7a, x86_64 and x86.
- Gradle and CI now support a persistent private signing key for both Debug and Release APKs. The key is supplied only through GitHub Actions Secrets and is never committed to the public repository.
- A Windows helper script creates the private signing key and the four values that the repository owner must add as Actions Secrets. Until all four secrets exist, CI builds and tests Debug as usual and intentionally skips signed Release APK generation.
- APKs built before the persistent key is configured may not be upgrade-compatible with APKs signed by the new key. Do not uninstall an existing app until a data-safe migration path is prepared.

## Windows — first operational client milestone 0.2.0-dev
- WPF UI is connected to an external sing-box process; the previous disabled placeholder button has been removed.
- CI downloads the official sing-box v1.14.4 Windows x64 archive from SagerNet and verifies its pinned SHA-256 before including the engine in the package.
- The Windows app accepts VLESS + REALITY links using TCP only; duplicate query keys, invalid UUIDs, malformed Reality public keys, unsupported transports, non-Reality links and unsupported flow values are rejected.
- A full-tunnel TUN configuration is generated with IPv4/IPv6 addressing, auto_route, strict_route, DNS hijacking and Cloudflare DNS-over-HTTPS. Private and link-local ranges are excluded to preserve LAN access.
- Every generated configuration is checked with the same pinned sing-box binary before starting the tunnel.
- The client requires administrator approval through Windows UAC to create the TUN adapter and modify routes.
- Session configuration is held in a delete-on-close file under the current user's LocalAppData and removed when the engine exits. Engine stdout/stderr are not written to logs.
- A Windows Job Object configured with KILL_ON_JOB_CLOSE supervises the engine process so closing the app also terminates the tunnel process tree.
- The UI only marks the IPv4 egress check successful when a fresh HTTPS lookup at api.ipify.org shows a public IPv4 address different from the pre-connection address. This is an egress check, not a DNS/IPv6 leak audit.
- The Windows package contains the upstream sing-box GPL notice and source reference. The app is not affiliated with SagerNet.
- A verification console project tests valid and invalid profile cases and invokes sing-box config validation on the generated TUN/DoH/VLESS configuration.

## Backend — VPN Gate ingestion
- Added an opt-in adapter for the official HTTPS CSV API.
- Validates CSV fields, public IPv4 addresses, OpenVPN profile size, matching remote host, port and TCP transport.
- Performs bounded TCP reachability probes and excludes non-responsive candidates.
- Stores no downloaded .ovpn profile contents in the public catalogue or server-list API.
- Separates source-reported ping from measured TCP connect latency.
- Replaces only VPN Gate-owned Free catalogue rows and preserves managed Premium rows.
- Refuses empty refreshes so a source outage cannot erase the last known-good list.
- Backend refresh is opt-in with RAAHAT_VPNGATE_ENABLED=true and runs every 20 minutes. This server-side adapter currently publishes TCP-probed records only.

## iOS
- Swift shared core and a minimal SwiftUI app target exist.
- PacketTunnelProvider intentionally fails closed until a reviewed Apple Network Extension adapter is linked.
- The Apple Packet Tunnel is not operational yet; Apple developer team signing, provisioning profile, app group, tunnel entitlement approval and on-device validation remain outstanding.

## Important limitations
- CI compilation and sing-box config validation do not prove that a VPN connection works on a real Windows PC. No physical Windows routing, network-adapter, DNS/IPv6 leak, Wi-Fi handover, or crash test has been completed.
- Windows supports only VLESS + REALITY over TCP in this milestone. Automatic free-server discovery, OpenVPN UI, per-app split tunneling, Windows Kill Switch/Always-on, automatic reconnection and signed in-app updates are not implemented.
- Windows checks IPv4 egress using api.ipify.org. The provider can see the request's public IP. DNS and IPv6 behavior require separate independent tests.
- If the Windows engine exits unexpectedly, Windows may remove its TUN routes; this milestone is not a system Kill Switch and must not be treated as such.
- Encrypted Android configuration recovery and system Always-on / lockdown behavior compile in CI but still require physical-device verification across reboot, service termination, revoked permission and OS vendor variants. Automatic status reporting is only available from Android 10 onward because the public VpnService query methods were added in API 29. Users must enable Android’s Block connections without VPN switch manually.
- Volunteer-run public relays may be slow, unstable or log traffic under policies outside Raahat's control. Do not use them for sensitive traffic.
- Premium authentication, short-lived leases, payments, managed server provisioning and admin UI are not implemented.
- PostgreSQL/Redis persistence is not implemented; the dynamic Backend catalogue is in-memory.
- iOS sing-box Packet Tunnel integration and Apple signing/provisioning are not implemented.
- Signed in-app update service is not implemented.
- Physical-device verification for DNS leaks, IPv6 handling, kill switch, split tunneling, Wi-Fi/mobile handover and battery use remains outstanding.

## Release rule
CI compilation, unit tests and config parsing are necessary but are not proof of a working VPN connection. Do not publish this development build as a production-ready VPN until live server tests, independent security review and real-device testing pass.
