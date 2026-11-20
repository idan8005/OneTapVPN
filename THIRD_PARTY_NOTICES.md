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

## VPN Gate directory

The application reads the public volunteer server directory at
`https://www.vpngate.net/api/iphone/`. VPN Gate is an independent service;
it does not operate under the Raahat VPN project.

## Android

Android, AndroidX, Material Components, and the Android SDK are trademarks
and/or software of their respective owners. Their respective licenses apply.
