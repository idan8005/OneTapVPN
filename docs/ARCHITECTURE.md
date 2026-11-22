# Raahat VPN — Architecture

## Product boundaries
Raahat is a hybrid service:
- Free: public-server ingestion through explicitly configured source adapters.
- Premium: managed infrastructure with authenticated, short-lived connection leases.
- Both tiers share a client-facing catalogue, but the API must never expose private keys, profile blobs or reusable Premium credentials.

WireGuard is excluded by product decision. Android uses sing-box 1.14.4 for VLESS + REALITY and its OpenVPN Client endpoint over TCP or UDP.

## Logical architecture
1. Native clients: Android, Windows and iOS.
2. Platform tunnel adapters: Android VpnService, Windows TUN/service adapter, and iOS Network Extension.
3. Engine adapters: sing-box and OpenVPN. Platform APIs own tunnel lifecycle; the engine does not impersonate operating-system connection state.
4. Control plane: HTTPS API, authentication, catalogue, health, subscription and update services.
5. Operations: server probes, capacity telemetry, metrics, audit events and deployment secrets.

## Repository modules
- backend/internal/domain: transport-independent server metadata and validation.
- backend/internal/catalog: static and atomically refreshed in-memory catalogue repositories.
- backend/internal/discovery: bounded adapters for public server directories; VPN Gate is the first adapter.
- backend/internal/httpapi: versioned metadata API.
- clients/android: VpnService host, libbox engine adapter, VLESS + REALITY parser, OpenVPN profile parser and VPN Gate discovery client.
- clients/*: platform-specific presentation, secure storage and tunnel integration.
- contracts/openapi.yaml: public API contract.

## Security invariants
- HTTPS is mandatory for remote API and public-source access.
- Public catalogue responses contain metadata only; they never contain credentials, private keys or raw OpenVPN profile blobs.
- Public-server checks are labelled by evidence level. TCP reachability is not an authenticated VPN handshake or proof that tunneled traffic works.
- UDP candidates cannot be verified by a TCP connect test; the Android client verifies them through an actual OpenVPN handshake and HTTPS traffic check.
- VPN Gate profiles are parsed in memory. External file references and unsupported profile features are rejected.
- Public credentials and private configuration are never logged or persisted by the VPN Gate discovery path.
- Tokens and Premium private configuration are stored only in platform secure storage.
- A client reports Connected only after the operating-system tunnel reports connected and independent HTTPS traffic verification succeeds.
- Android Always-on support relies on an AES-GCM encrypted last-known tunnel configuration protected by an Android Keystore key; Android itself enforces lockdown only after the user enables Block connections without VPN in system settings.
- Update packages must be authenticated by a trusted signature in addition to a digest.
- Public volunteer-operated servers are not trusted Premium infrastructure and must be identified as community-provided.

## Current implementation slices
- Clean multi-platform project foundation, API contract and CI.
- Android sing-box VLESS + REALITY tunnel host with VpnService, TUN lifecycle, protected engine sockets and HTTPS traffic verification.
- Android OpenVPN Client endpoint based on sing-box, with in-memory profile parsing and VPN Gate discovery for both TCP and UDP profiles.
- TCP entries are prefiltered by a bounded connect probe; UDP entries are labelled unprobed until the real VPN handshake and traffic check.
- Android automatically tries the fastest candidates and verifies real tunneled HTTPS traffic.
- Backend VPN Gate source adapter: official HTTPS CSV ingestion, public IPv4/profile validation, bounded TCP checks, and source-scoped catalogue refresh.
- Server metadata marks whether packet loss or capacity is actually known; missing measurements are not represented as zero load or zero loss.
- VPN Gate Backend refresh is opt-in with RAAHAT_VPNGATE_ENABLED=true, runs every 20 minutes, and preserves the last known-good list on failure.

## Not yet production-ready
- Real-device validation of Android OpenVPN TCP/UDP endpoints and public VPN Gate server compatibility.
- Windows and iOS working tunnel engines.
- Premium authentication, leases, payments, managed server provisioning and administration.
- Durable PostgreSQL/Redis catalogue persistence and deployed backend.
- Signed update service and independent security review.
