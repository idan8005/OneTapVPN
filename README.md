# راحت وی‌پی‌ان | Raahat VPN

A Persian-first Android VPN client with two separate connection engines: OpenVPN for public VPN Gate relays, and sing-box for user-supplied VLESS + REALITY links. **WireGuard is not used.**

## Features

- **Automatic free-server discovery:** downloads the public VPN Gate directory.
- **Best-candidate selection:** ranks servers using their reported network
  metrics; OpenVPN then attempts a real connection.
- **Automatic failover:** if a relay rejects the connection, times out or drops
  during setup, the app tries the next candidate.
- **One-tap connect / disconnect:** Android's VPN permission is required once.
- **Full tunnel:** all apps use the VPN.
- **Selected apps:** only the apps checked in the app picker use the VPN.
  Your selection is saved on the device.
- **In-app update:** checks the latest GitHub Release, downloads the APK,
  verifies its SHA-256 checksum, and opens the Android package installer.
- **Persian and English UI.**
- **Iran compatibility mode:** import a VLESS + REALITY TCP link and run it through the sing-box Android engine.

## Iran compatibility mode

The Iran mode uses the sing-box Android library and accepts a user-supplied
VLESS + REALITY share link. In this version the link must use TCP and include
a valid UUID, `security=reality`, `sni`, `pbk` and `sid`; an optional
`fp` and `flow=xtls-rprx-vision` are supported. WebSocket and gRPC links
are not accepted by this parser yet.

Import the link in the **Iran compatibility mode** card, then tap
**Connect with Iran mode** and approve Android's VPN permission. The profile
is stored in the app's private local preferences. It is not uploaded by the
app. Selective-app routing uses the same list of chosen Android packages;
leaving the selection empty uses full-tunnel routing.

This adds a protocol option that may work on networks where the current
VPN Gate/OpenVPN path is blocked. It does **not** guarantee that Iran's
carriers or filtering systems will allow a particular server. A working
VLESS + REALITY server is required; this build does not supply a free server
subscription or promise automatic discovery of public VLESS servers.

## Important: free volunteer servers

VPN Gate relays are operated by volunteers, not by Raahat VPN. Published
speed/ping values are directory measurements, not a guarantee of the speed
you will receive. A server can go offline, become congested, or reject
connections. Raahat automatically tries another candidate, but cannot promise
that a free relay will always be available or fast.

The relay operator controls the server. Do not use unknown volunteer relays
for passwords, banking, private work, or other sensitive traffic. Read VPN
Gate's own logging and privacy information before use:
https://www.vpngate.net/en/about_abuse.aspx

## Install and use

1. Install the APK.
2. Open **راحت وی‌پی‌ان**.
3. Choose **فول‌تانل** or **برنامه‌های انتخابی**.
4. In selected-app mode, choose at least one app.
5. Tap **اتصال** and approve Android's VPN permission prompt.

The first server list refresh requires internet access. If the first server is
unreachable, the app automatically tries another ranked candidate.

Android requires the user to approve VPN access on first connection. Android
also controls the final APK installation confirmation during an update; a
regular app cannot silently replace itself.

## Build from source

Requirements: JDK 17, Android SDK platform 34 and Build Tools 34.0.0. Gradle also resolves the sing-box Android library from JitPack.

The OpenVPN engine is included as a pinned Git submodule:

```bash
git clone --recurse-submodules https://github.com/idan8005/OneTapVPN.git
cd OneTapVPN
gradle --no-daemon :app:assembleDebug
```

To initialize the pinned module in an existing clone:

```bash
git submodule update --init --recursive
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions and signed releases

Every push and pull request builds and tests the debug APK. A signed Release
APK requires the four existing repository Actions secrets documented in
[RELEASE_SIGNING_FA.md](RELEASE_SIGNING_FA.md). Keep using the same private
keystore for every release; Android only permits an in-place update when the
signing certificate matches.

To publish a version for the in-app updater:

1. Make sure the repository is public, or configure a public unauthenticated
   release endpoint. The current repository is private, so normal app
   installations cannot read its GitHub Releases API without authentication.
2. Configure the four Release signing secrets.
3. Push a version tag such as `v1.4.0`.
4. The workflow builds, signs, verifies and publishes
   `raahat-vpn.apk` and `raahat-vpn.apk.sha256` to the GitHub Release.

The updater expects those exact asset names and compares the release tag to
the installed app version. It downloads and verifies the APK before handing
it to Android's installer. The user may need to allow installation from
Raahat VPN in Android settings and must confirm the OS installer prompt.

## Project layout

- `app/`: application UI, server discovery, routing choices and updater.
- `third_party/OpenGateVPN/vpnLib/`: OpenVPN Android engine.
- `IranVpnService.kt`: Android VPN service for the optional sing-box engine.
- `IranVpnProfile.kt`: parser for user-provided VLESS + REALITY TCP links.
- `VpnGateApi.kt`: public directory fetch and server ranking.
- `VpnManager.kt`: OpenVPN profile creation, per-app routing and tunnel start.
- `VpnViewModel.kt`: connection state and automatic server failover.
- `AppUpdater.kt`: release discovery, APK download and checksum validation.

## Licensing

The bundled OpenVPN engine and the sing-box libbox engine are GPL-3.0. This application and its redistribution must preserve the upstream notices and comply with the GPLv3 obligations.
See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Physical device testing

CI compilation and emulator UI tests do not prove that a specific phone,
carrier, Wi-Fi network or VPN Gate relay completes a real tunnel. Test on a
physical Android phone before relying on the app. Never submit private data
or credentials through volunteer relays.
