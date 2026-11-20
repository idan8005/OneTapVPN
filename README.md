# Raahat VPN for Android

**Raahat VPN (راحت وی‌پی‌ان)** is a lightweight Android VPN client built on the WireGuard tunnel library.

## Current version

- Version: **1.3.0**
- Version code: **4**
- Android package: `com.example.onetapvpn` (kept stable so existing Release installations can update)
- Minimum Android: Android 7.0 (API 24)
- Java: 17

## Simple start

1. Install the APK.
2. Open **Raahat VPN**.
3. Tap **Connect**. On the first connection only, approve Android's VPN permission prompt. Raahat VPN then attempts to obtain and save its WARP profile automatically.

The app also connects automatically each time it is opened (not when you return to it or rotate the screen). After Android permission has been approved, you can also connect or disconnect with one tap. No account, API key, manual server selection, or initial WireGuard profile import is required for the automatic WARP attempt.

Manual WireGuard import/export is still available under **Advanced settings (optional)** and is hidden from the normal start screen.

## Build and test

Every push to `main` or `master` runs:

1. Android Lint.
2. JVM unit tests.
3. Debug APK build and verification.
4. Android emulator UI smoke test of the one-tap home screen and optional advanced profile panel.
5. If the four release-signing Actions secrets exist, signed Release APK build and signature verification.

The workflow publishes `RaahatVPN-Debug-APK` and, when signing secrets are configured, `RaahatVPN-Release-1.3.0` with the signed APK, SHA-256 checksum, and signing verification output.

See [RELEASE_SIGNING_FA.md](RELEASE_SIGNING_FA.md) for the one-time setup of the four GitHub Actions secrets. The private keystore must never be committed to this repository.

## Stable signing and updates

All Release APKs must use the same private keystore and a higher version code to update in place without uninstalling. The Android package ID remains unchanged intentionally.

The earlier 1.1.0 debug APK used a temporary debug certificate. Android will not allow a fixed-key Release APK to update that debug build in place. If that old debug build is installed, see the migration instructions in [DEVICE_TEST_PLAN.md](DEVICE_TEST_PLAN.md) before uninstalling it.

## Connection behaviour and limits

- **Automatic WARP profile:** On first connection, the app attempts to create a free Cloudflare WARP profile. The consumer registration endpoint used is unofficial for third-party clients and may be changed or blocked without notice. Automatic provisioning is not guaranteed.
- **Your own WireGuard profile:** Import a `.conf` file or paste its contents from Advanced settings. Profiles are size-limited and validated before saving.
- **Profile export:** Export a saved profile from Advanced settings. The exported file contains the private key; protect it and delete it after importing it on the destination device.

Profiles are encrypted with AES-GCM using an Android Keystore key. App backup and device-transfer backup are disabled so device-bound encrypted profiles are not restored without their key.

## Physical-device validation

GitHub Actions can compile, lint, run JVM tests, and launch the UI on an emulator. This does not prove that a real phone, carrier/Wi-Fi network, DNS resolver, and VPN handshake work correctly. Follow [DEVICE_TEST_PLAN.md](DEVICE_TEST_PLAN.md) on a physical Android phone and share only redacted diagnostics.

## Important Android limitation

Android itself requires the user to approve VPN access on first connection. Raahat VPN cannot silently bypass this system security prompt. After approval, the app can connect and disconnect with one tap.

WARP does not provide arbitrary country selection, and this project does not promise unlimited service, a particular speed, or guaranteed availability.
