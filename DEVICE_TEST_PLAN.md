# Raahat VPN 1.3.0 — Android device test plan

This checklist is for a physical Android phone. GitHub Actions can compile, lint, run JVM tests, and launch the UI on an emulator, but it cannot prove that a real phone, carrier, Wi-Fi network, DNS resolver, and WARP registration endpoint complete a working tunnel.

## First install and first connection

1. Configure the four Actions secrets listed in `RELEASE_SIGNING_FA.md`.
2. Run the latest GitHub Actions workflow and download `RaahatVPN-Release-1.3.0`.
3. Verify the APK signature and checksum from the artifact.
4. Install it on an Android phone running Android 7.0 or later.
5. Open **راحت وی‌پی‌ان** and tap **اتصال**.
6. On the first connection, approve Android's VPN permission prompt. The app then attempts to create and save a WARP profile automatically.

The Android VPN prompt is a required operating-system security confirmation and cannot be skipped by the app. After the user approves it once, subsequent connection attempts use the single Connect button.

## Important one-time migration

The older 1.1.0 APK was signed with a temporary GitHub Actions debug certificate. Android will reject an in-place update from that APK to the fixed-key Release APK because the signatures differ.

- If 1.1.0 is not installed, install the signed Release APK directly.
- If 1.1.0 is installed and the original WireGuard profile is available, keep a copy of that profile, uninstall the old Debug app, install Release, and import the profile again under Advanced settings.
- If 1.1.0 is installed and the only copy of the profile is inside that app, do not uninstall it yet. The old build does not contain the profile-export feature, and uninstalling clears app-private data. Contact the developer before removing it.

Starting with the first fixed-key Release, later Release APKs signed with this same keystore can update in place without uninstalling.

## Manual test cases

| Test | Procedure | Expected result |
|---|---|---|
| Clean install | Install Release APK | Android shows the app as راحت وی‌پی‌ان with the new shield/lock logo |
| Simple home screen | Open the app | Connect button is the main action; manual profile tools are hidden |
| First permission | Tap Connect on a fresh install | Android shows its VPN consent dialog |
| Permission cancel | Cancel the Android dialog | App stays disconnected and explains permission was not granted |
| First connection | Approve the system prompt | App attempts automatic WARP profile provisioning and starts the tunnel if provisioning succeeds |
| Reconnect | Disconnect, then tap Connect again | Reuses the saved profile; no profile import or setup is needed |
| Advanced settings | Open Advanced settings (optional) | Manual WireGuard import, paste, and export controls appear |
| Import profile | Import a valid WireGuard `.conf` | App validates and stores the profile encrypted |
| Data path | With a valid server, open a normal HTTPS page and a DNS test page | Traffic passes through the configured tunnel |
| Disconnect | Tap Disconnect | Tunnel stops and normal network access returns |
| Relaunch | Close/reopen app while connected | App reads the backend state and displays the correct status |
| Export profile | Use Export WireGuard profile in Advanced settings | A `.conf` file is saved; treat it as a secret because it includes the private key |
| Update | Install a later APK signed by the same Release key using the Android package installer | Update succeeds without uninstalling or deleting app data |

## Collect diagnostics on Windows

With Android Platform Tools installed and USB debugging enabled:

```powershell
adb devices
adb logcat -c
adb logcat -v time | findstr /i "RaahatVPN WireGuard GoBackend VpnService"
```

In another PowerShell window, inspect VPN/network state:

```powershell
adb shell dumpsys connectivity | findstr /i "vpn"
adb shell dumpsys package com.example.onetapvpn
```

Stop logcat with Ctrl+C. Review the output before sharing it; do not send WireGuard private keys, QR codes, passwords, or full configuration files.

## Report back

Record the phone model, Android version, connection type (Wi-Fi/mobile), whether WARP or a personal WireGuard profile was used, the test that failed, and the relevant redacted log lines.
