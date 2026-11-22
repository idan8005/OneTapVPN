# Free-server discovery: VPN Gate

Raahat's first public Free source is the official VPN Gate HTTPS endpoint:

- https://www.vpngate.net/api/iphone/
- Project guide: https://www.vpngate.net/en/howto_openvpn.aspx

## Android app
The Android app retrieves the public CSV directly over HTTPS, so the user does not need to enter a server address or API key. It:
- Parses the CSV and embedded OpenVPN profile in memory.
- Accepts public IPv4 records only and validates the remote endpoint against the source record.
- Supports TCP and UDP profile transports supported by sing-box 1.14.4.
- Rejects external certificate/key/authentication file paths and vulnerable compression settings.
- Caps response size, candidate count, concurrency and connection timeout.
- Runs bounded TCP reachability probes for TCP profiles and hides non-responsive TCP endpoints.
- Does not pretend a TCP probe can verify UDP. UDP candidates are labelled as pending real connection validation.
- Ranks candidates using source-reported latency and quality, then automatically tries up to three.
- Passes each selected profile to sing-box's OpenVPN Client endpoint and validates the generated config with libbox before starting the VPN tunnel.
- Resolves a DNS test hostname and probes dedicated HTTPS IPv4 and IPv6 endpoints after the TUN is established. DNS and public IPv4 are required before the app reports Connected; IPv6 reachability is displayed as informational only.
- Remembers only the stable relay ID and failure timestamp locally; a relay that fails the real connection check is hidden and excluded from automatic selection for 15 minutes, then becomes eligible for a fresh check.

The full profile and the VPN Gate public username/password are held in memory only. They are not saved to disk or sent to the metadata API. Probe IP response bodies are evaluated in memory and are not stored or logged. The public API never returns raw profiles or credentials.

## Backend source adapter
The Go Backend has a separate opt-in adapter for the same official HTTPS CSV source. It currently imports TCP OpenVPN profiles, probes their ports and replaces only VPN Gate-owned Free catalogue entries. It preserves Premium entries and refuses empty refreshes.

Enable periodic Backend refresh with:

    RAAHAT_VPNGATE_ENABLED=true

The adapter refreshes every 20 minutes.

## Reliability and privacy limits
A successful TCP connect is only a preliminary reachability signal. It does not prove OpenVPN authentication or tunnel traffic. UDP candidates cannot be confirmed with a TCP port test. For both types, the Android client must complete the VPN handshake and HTTPS traffic check. An IPv6 HTTPS response alone cannot distinguish VPN-routed IPv6 from a path that bypasses the tunnel; a real-device leak test comparing the protected and unprotected network paths is still required. Volunteer VPN Gate relays are operated by third parties and may be slow, unstable or log traffic under policies outside Raahat's control. They must be clearly labelled public/community servers, never as private or Premium infrastructure, and should not be used for sensitive traffic.
