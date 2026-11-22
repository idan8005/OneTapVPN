# Raahat VPN — Architecture

## Product boundaries
Raahat is a hybrid service:
- Free: public-server ingestion through explicitly configured source adapters.
- Premium: managed infrastructure with authenticated, short-lived connection leases.
- Both tiers share one client-facing catalogue, but the API must never expose private keys, profile blobs or reusable Premium credentials.

WireGuard is excluded by product decision. The current Android engine milestone targets sing-box VLESS + REALITY. OpenVPN is the first public Free source protocol, but its Android client engine is not integrated yet.

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
- clients/*: platform-specific presentation, secure storage and tunnel integration.
- contracts/openapi.yaml: public API contract.

## Security invariants
- HTTPS is mandatory for remote API access.
- Public catalogue responses contain metadata only; they never contain credentials, private keys or raw OpenVPN profile blobs.
- Public-server checks are labelled by evidence level. TCP reachability is not an authenticated VPN handshake or proof that tunneled traffic works.
- Tokens and private configuration are stored only in platform secure storage.
- A client reports Connected only after the operating-system tunnel reports connected and independent traffic verification succeeds.
- Update packages must be authenticated by a trusted signature in addition to a digest.
- Logs must not contain credentials, private keys, full connection URIs or user traffic.
- Public volunteer-operated servers are not trusted Premium infrastructure and must be identified as community-provided.

## Current implementation slices
- Clean multi-platform project foundation, API contract and CI.
- Android sing-box VLESS + REALITY tunnel host with VpnService, TUN lifecycle, protected engine sockets and actual HTTPS traffic verification.
- Backend VPN Gate source adapter: official HTTPS CSV ingestion, public IPv4 and profile validation, bounded TCP reachability checks, and source-scoped catalogue replacement.
- Server metadata marks whether packet loss or capacity is actually known; missing measurements must not be represented as zero load or zero loss.
- VPN Gate refresh is opt-in with RAAHAT_VPNGATE_ENABLED=true and runs in the backend every 20 minutes. An empty/failed refresh does not delete the last known-good entries.

## Not yet production-ready
- Android OpenVPN engine, connection profile delivery and client integration with the public catalogue.
- Windows and iOS working tunnel engines.
- Premium authentication, connection leases, payments, managed server provisioning and administration.
- Durable PostgreSQL/Redis catalogue persistence and deployed backend.
- Signed update service and real-device validation.
