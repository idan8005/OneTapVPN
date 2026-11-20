# راحت وی‌پی‌ان | Raahat VPN

A Persian-first Android VPN client with two connection engines: OpenVPN for public VPN Gate relays, and sing-box for automatically discovered VLESS + REALITY servers. **WireGuard is not used.**

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
- **Automatic Iran server discovery:** checks three public feeds, filters VLESS + REALITY TCP/RAW profiles and builds an automatic sing-box URL-test group.
- **Automatic candidate selection:** sing-box tests candidates through the phone's current network and selects the fastest responsive node, then re-tests periodically.

## Automatic Iran connection

The Iran mode requires no manually pasted server link. On app launch, it checks
several public feeds and their CDN mirrors, extracts VLESS + REALITY profiles,
filters malformed or unsupported entries, removes duplicates and keeps up to
100 candidates. The feed sources currently include:

- `mehrtat/vless-collector` (plain VLESS feed)
- `0xRadikal/Free-v2ray-Configs` (verified public configurations)
- `Au1rxx/free-vpn-subscriptions` (base64 subscription feed)

The app builds a sing-box `urltest` group. Health checks are performed by the
sing-box engine through the phone's current network, not by trusting latency
values reported by a remote list. The selected node is re-tested periodically;
when the network changes or a candidate stops responding, sing-box can select
another candidate. The candidate list is refreshed after 20 minutes, or
manually from **Find and refresh servers**.

Tap the main **Connect** button to use automatic Iran mode. Android's VPN
permission is required the first time. **Use classic VPN Gate servers** remains
available as a separate fallback. Full-tunnel and selected-app routing are
supported by both modes.

Public feeds and free nodes are untrusted and can disappear at any time. The
feed maintainers and server operators are independent third parties; Raahat VPN
does not operate or audit their infrastructure. A node can pass a connection
test and still be slow, unstable or unsafe. Do not use public relays for
banking, passwords, private work or other sensitive traffic. Automatic
discovery does not guarantee that any particular node will be reachable from
every Iranian carrier or Wi-Fi provider.

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

Server discovery requires access to at least one public feed or CDN mirror. The
app attempts more than one source and caches the last successful candidate set.

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
3. Push a version tag such as `v1.5.0`.
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
- `IranVpnProfile.kt`: strict VLESS + REALITY parser and sing-box URL-test builder.
- `IranServerDiscovery.kt`: multi-source public feed discovery with CDN fallback.
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
