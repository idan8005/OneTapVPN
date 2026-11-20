# Third-party notices

## OpenVPN for Android / vpnLib

Raahat VPN bundles the `vpnLib` Android module from
[IyedAmiri/OpenGateVPN](https://github.com/IyedAmiri/OpenGateVPN), which is
based on [OpenVPN for Android (ics-openvpn)](https://github.com/schwabe/ics-openvpn)
by Arne Schwabe.

The bundled VPN engine is distributed under **GNU General Public License v3
(GPL-3.0)**. The complete license text is in [LICENSE](LICENSE). The
`third_party/OpenGateVPN` Git submodule is pinned to commit
`12f8ea97976f1bac0307a013b40712098b865d43`.

Because the application is based on and distributed together with this
GPL-covered engine, redistributions must comply with the applicable GPLv3
source-code, attribution, license-notice, and corresponding-source
requirements. Preserve the upstream copyright and license notices in the
submodule.

## sing-box Android engine (libbox)

The optional Iran compatibility mode uses the Android `libbox` library from
[singbox-android/libbox](https://github.com/singbox-android/libbox), built on
the [SagerNet/sing-box](https://github.com/SagerNet/sing-box) core. Raahat VPN
resolves version `1.14.4` from JitPack. The library is distributed under
**GNU General Public License v3 (GPL-3.0)**. Its source, license and upstream
notices must be preserved when redistributing the application. The library
is fetched as a build dependency; it is not vendored into this repository.

## VPN Gate directory

The application reads the public volunteer server directory at
`https://www.vpngate.net/api/iphone/`. VPN Gate is an independent service;
it does not operate under the Raahat VPN project.

## Android

Android, AndroidX, Material Components, and the Android SDK are trademarks
and/or software of their respective owners. Their respective licenses apply.
