# OneTap VPN (Android)

A minimal Android WireGuard client with a one-tap connect/disconnect interface.

## Build APK

Every push to `main` or `master` starts the **Build OneTapVPN APK** GitHub Actions workflow. You can also start it manually from the Actions tab.

When the workflow succeeds, download the `OneTapVPN-APK` artifact. It contains `app-debug.apk`, a debug-signed APK suitable for direct installation and testing.

## VPN setup and limitations

On first connection, Android displays its mandatory VPN consent dialog. If no WireGuard profile has been saved, the app attempts to register a Cloudflare WARP profile using an unofficial endpoint. Cloudflare may change or block that endpoint, so automatic provisioning is not guaranteed.

You can also import your own WireGuard configuration from the clipboard using the Advanced button. WARP does not provide arbitrary country selection, and this project does not promise unlimited availability or a particular speed.

See [README_FA.md](README_FA.md) for Persian instructions.
