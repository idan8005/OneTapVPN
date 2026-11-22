# Raahat VPN — Architecture

## Product boundaries
Raahat is a hybrid service:
- Free: public-server ingestion through explicitly configured source adapters.
- Premium: managed infrastructure with authenticated, short-lived connection leases.
- Both tiers share one client-facing catalogue, but the API must never expose private keys or reusable Premium credentials.

WireGuard is excluded by product decision. The initial engine integration targets sing-box and OpenVPN.

## Logical architecture
1. Native clients: Android, Windows and iOS.
2. Platform tunnel adapters: Android VpnService, Windows TUN/service adapter, and iOS Network Extension.
3. Engine adapters: sing-box and OpenVPN. Platform APIs own tunnel lifecycle; the engine does not impersonate operating-system connection state.
4. Control plane: HTTPS API, authentication, catalogue, health, subscription and update services.
5. Operations: server probes, capacity telemetry, metrics, audit events and deployment secrets.

## Repository modules
- backend/internal/domain: transport-independent server metadata and validation.
- backend/internal/catalog: catalogue loading and repository contract.
- backend/internal/httpapi: HTTP transport and response schemas.
- clients/*: platform-specific presentation, secure storage and tunnel integration.
- contracts/openapi.yaml: public API contract.

## Security invariants
- HTTPS is mandatory for remote API access.
- Public catalogue responses contain metadata only; they never contain credentials, private keys or Premium connection configuration.
- Tokens and private configuration are stored only in platform secure storage.
- A client reports Connected only after the operating-system tunnel reports connected and traffic verification succeeds.
- Update packages must be authenticated by a trusted signature in addition to a digest.
- Logs must not contain credentials, private keys, full connection URIs or user traffic.

## First implementation slice
The initial clean rewrite establishes a validated catalogue model, read-only versioned server-list API, TLS-aware backend startup, native client shells with an explicit unavailable-engine state, shared Swift core and CI. This is a foundation, not a production VPN release. The next slices are engine integration, provider adapters, authenticated Premium leases, persistence, admin operations, updates and real-device validation.
