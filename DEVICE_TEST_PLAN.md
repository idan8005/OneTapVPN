# OneTapVPN 1.2.0 — Android device test plan

This checklist is for a physical Android phone. GitHub Actions can compile, lint, run JVM tests, and launch the UI on an emulator, but it cannot prove that a real phone, carrier, Wi-Fi network, DNS resolver, and WARP registration endpoint complete a working tunnel.

## Before testing

1. Configure the four Actions secrets listed in `RELEASE_SIGNING_FA.md`.
2. Run the latest GitHub Actions workflow and download `OneTapVPN-Release-1.2.0`.
3. Verify the APK signature and checksum from the artifact.
4. Install it on an Android phone running Android 7.0 or later.
5. Use a known-good WireGuard `.conf` file for the network test. Automatic WARP provisioning is optional and depends on an unofficial Cloudflare consumer endpoint.

## Important one-time migration

The older 1.1.0 APK was signed with a temporary GitHub Actions debug certificate. Android will reject an in-place update from that APK to the fixed-key Release APK because the signatures differ.

- If 1.1.0 is not installed, install the signed Release APK directly.
- If 1.1.0 is installed and the original WireGuard profile is available, keep a copy of that profile, uninstall the old Debug app, install Release, and import the profile again.
- If 1.1.0 is installed and the only copy of the profile is inside that app, do not uninstall it yet. The old build does not contain the new profile-export feature, and uninstalling clears app-private data. Contact the developer before removing it.

Starting with the first fixed-key Release, later Release APKs signed with this same keystore can update in place without uninstalling.

## Manual test cases

| Test | Procedure | Expected result |
|---|---|---|
| Clean install | Install Release APK | Package installs and OneTapVPN opens |
| Permission | Tap Connect on a fresh install | Android shows its VPN consent dialog |
| Permission cancel | Cancel the Android dialog | App stays disconnected and explains permission was not granted |
| Import profile | Import a valid WireGuard `.conf` | App validates and stores the profile encrypted |
| Connect | Tap Connect and approve system permission | UI changes to Connected only after backend reports UP |
| Data path | With a valid server, open a normal HTTPS page and a DNS test page | Traffic passes through the configured tunnel |
| Disconnect | Tap Disconnect | Tunnel stops and normal network access returns |
| Relaunch | Close/reopen app while connected | App reads the backend state and displays the correct status |
| Export profile | Use Export WireGuard profile | A `.conf` file is saved; treat it as a secret because it includes the private key |
| Re-import | Import the exported file after app reinstall | Profile validates and can be used again |
| Update | Install a later APK signed by the same Release key using the Android package installer | Update succeeds without uninstalling or deleting app data |

## Collect diagnostics on Windows

With Android Platform Tools installed and USB debugging enabled:

```powershell
adb devices
adb logcat -c
adb logcat -v time | findstr /i "OneTapVPN WireGuard GoBackend VpnService"
```

In another PowerShell window, inspect VPN/network state:

```powershell
adb shell dumpsys connectivity | findstr /i "vpn"
adb shell dumpsys package com.example.onetapvpn
```

Stop logcat with Ctrl+C. Review the output before sharing it; do not send WireGuard private keys, QR codes, passwords, or full configuration files.

## Report back

Record the phone model, Android version, connection type (Wi-Fi/mobile), whether WARP or a personal WireGuard profile was used, the test that failed, and the relevant redacted log lines.
