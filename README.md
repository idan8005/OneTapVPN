# OneTap VPN for Android

OneTap VPN is a lightweight Android client built on the official WireGuard tunnel library. It supports a one-tap connect/disconnect flow, automatic first-run WARP profile provisioning, and importing an existing WireGuard `.conf` profile.

## Build an APK

A push to `main` or `master` runs GitHub Actions. The workflow performs Android lint, unit tests, builds the debug APK, verifies that the APK exists, and uploads it as the `OneTapVPN-APK` artifact.

1. Open the repository's **Actions** tab.
2. Open the latest successful **Build and verify OneTapVPN APK** run.
3. Download `OneTapVPN-APK` from the Artifacts section and extract `app-debug.apk`.
4. Open the APK on an Android device and approve Android's installation prompt.
5. On the first VPN connection, approve the separate Android VPN permission prompt.

The debug APK is suitable for direct installation and testing. A public Play Store release requires a separately managed release-signing key and a release build. Hosted debug builds use the runner's debug signing certificate; installing a build signed by a different runner may require uninstalling the previous debug build first. A stable update path requires a user-managed signing key stored in GitHub Actions Secrets.

## Connection options

- **Automatic WARP profile:** On the first connection, the app attempts to create a free Cloudflare WARP profile. Cloudflare's consumer registration endpoint used by this feature is unofficial for third-party clients and may be changed or blocked without notice. Automatic provisioning is therefore not guaranteed.
- **Your own WireGuard profile:** Import a `.conf` file or paste its contents from the clipboard. The profile is size-limited and validated before it is saved.

Profiles are encrypted with AES-GCM using a key held by Android Keystore. App backup and device-transfer backup are disabled for app data so encrypted profiles are not restored without their device-bound key. A legacy plaintext profile from older builds is migrated to encrypted storage on first read.

## Improvements in version 1.1.0

- Serialized VPN operations to prevent rapid repeated taps from starting concurrent backend/network work.
- Correctly disconnects without re-registering WARP or parsing a profile.
- Restores the UI status from the active WireGuard backend when the app returns to the foreground.
- Uses the current published WireGuard tunnel library version.
- Validates imported and automatically provisioned profiles before storing/connecting.
- Encrypts stored WireGuard private keys using Android Keystore.
- Adds Persian localization, a launcher icon, import-from-file support, and Android backup exclusions.
- Disables Android Always-on VPN until a persistent Always-on callback/profile-restoration path is implemented and tested.

## Important limitations

- Android requires the user to approve VPN access the first time. An app cannot bypass that operating-system consent dialog.
- Cloudflare WARP does not provide arbitrary country selection, and this project does not promise unlimited service, a particular speed, or guaranteed availability.
- The current artifact is a debug-signed APK. Do not distribute it as a production release.

## Project details

- Android package: `com.example.onetapvpn`
- App version: `1.1.0` (version code 2)
- Minimum Android: Android 7.0 (API 24)
- Java: 17
- WireGuard tunnel library: `1.0.20260102`
