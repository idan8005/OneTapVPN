# Free-server discovery: VPN Gate

Raahat's first public Free source adapter reads only the official HTTPS endpoint:

- https://www.vpngate.net/api/iphone/
- Project guide: https://www.vpngate.net/en/howto_openvpn.aspx

The adapter:
- Parses the official CSV and decodes the OpenVPN profile only in memory.
- Accepts public IPv4 records only; private, loopback, link-local, multicast, documentation, and CGNAT ranges are rejected.
- Validates that the profile remote host matches the source record and only accepts TCP OpenVPN profiles for the current TCP reachability probe.
- Caps response size, candidate count, concurrency and connection timeout.
- Publishes only candidates whose configured TCP endpoint accepts a connection.
- Labels this evidence `tcp-reachable`; it is not an OpenVPN authentication or end-to-end tunnel test.
- Keeps the profile blob and the public `vpn/vpn` credentials out of the public server catalogue.

Public VPN Gate relays are volunteer-operated. Their availability and performance change, and their operators may log activity. They are not suitable for sensitive traffic. Raahat must label these as public/community servers and never describe them as private or Premium infrastructure.

Set `RAAHAT_VPNGATE_ENABLED=true` to enable periodic directory refresh in the backend. A failed or empty refresh never deletes the last known good catalogue.
