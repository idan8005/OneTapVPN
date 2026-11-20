# OneTap VPN for Android

OneTap VPN is a lightweight Android client built on the official WireGuard tunnel library. It supports one-tap connect/disconnect, first-run WARP profile provisioning, importing and exporting WireGuard profiles, and encrypted on-device profile storage.

## Current version

- Version: **1.2.0**
- Version code: **3**
- Android package: `com.example.onetapvpn`
- Minimum Android: Android 7.0 (API 24)
- Java: 17
- WireGuard tunnel library: `1.0.20260102`

## Build and test

Every push to `main` or `master` runs:

1. Android Lint.
2. JVM unit tests.
3. An Android emulator UI smoke test.
4. Debug APK build and file verification.
5. If the four release-signing Actions secrets exist, signed Release APK build and signature verification.

The latest successful workflow provides `OneTapVPN-Debug-APK`. Once signing secrets are configured, it also publishes `OneTapVPN-Release-1.2.0` with the signed APK, SHA-256 checksum, and signing verification output.

See [RELEASE_SIGNING_FA.md](RELEASE_SIGNING_FA.md) for the four GitHub Actions secret names and the one-time setup instructions. The private keystore must never be committed to this repository.

## Stable signing and updates

The release APK is signed with a dedicated keystore. Keep that keystore and its passwords backed up. All future Release APKs must use the same key and a higher version code to update in place without uninstalling.

The older 1.1.0 APK was signed with a temporary debug certificate. Android will not allow the fixed-key Release APK to update that debug build in place. If the old build is installed, see the migration instructions in [DEVICE_TEST_PLAN.md](DEVICE_TEST_PLAN.md) before uninstalling it.

## Connection options

- **Automatic WARP profile:** On first connection, the app attempts to create a free Cloudflare WARP profile. The consumer registration endpoint used is unofficial for third-party clients and may be changed or blocked without notice. Automatic provisioning is not guaranteed.
- **Your own WireGuard profile:** Import a `.conf` file or paste its contents from the clipboard. Profiles are size-limited and validated before saving.
- **Profile export:** Export a saved WireGuard profile before moving installations. The exported file contains the private key; protect it and delete it after importing it on the destination device.

Profiles are encrypted with AES-GCM using an Android Keystore key. App backup and device-transfer backup are disabled so device-bound encrypted profiles are not restored without their key.

## Physical-device validation

GitHub Actions runs a UI smoke test on an Android emulator. This is not a substitute for a real phone, carrier/Wi-Fi, DNS, and VPN handshake test. Follow [DEVICE_TEST_PLAN.md](DEVICE_TEST_PLAN.md) on a physical Android phone and share only redacted diagnostics.

## Important limitations

- Android requires the user to approve VPN access on first connection.
- WARP does not provide arbitrary country selection, and this project does not promise unlimited service, a particular speed, or guaranteed availability.
- A successful build, emulator UI test, or signed APK verification does not prove a successful real-world VPN handshake.
