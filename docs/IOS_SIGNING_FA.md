# راهنمای امضای iOS و ساخت IPA توسعه

این مرحله فقط برای ساخت نسخه‌ای است که روی iPhone ثبت‌شده در حساب Apple Developer نصب شود. ساخت unsigned فعلی برای نصب مستقیم روی iPhone واقعی مناسب نیست.

## شناسه‌هایی که باید در Apple Developer ثبت شوند

در بخش Certificates, Identifiers & Profiles، دو App ID صریح (Explicit) بسازید:

| شناسه | Bundle ID |
|---|---|
| برنامه | `com.raahat.vpn` |
| افزونهٔ Packet Tunnel | `com.raahat.vpn.packet-tunnel` |

یک App Group با شناسهٔ دقیق `group.com.raahat.vpn` بسازید و آن را به هر دو App ID متصل کنید.

برای App ID افزونه، قابلیت Network Extensions را فعال کنید و نوع `packet-tunnel-provider` را مجاز کنید. این entitlement برای استفاده از `NEPacketTunnelProvider` لازم است.

دستگاه iPhone مقصد را در Devices ثبت کنید. برای هر دو Bundle ID یک پروفایل **iOS App Development** بسازید؛ هر دو باید شامل همان دستگاه و App Group باشند. پروفایل افزونه باید entitlement مربوط به `packet-tunnel-provider` را نیز داشته باشد.

مرجع رسمی Apple:
- [Network Extensions entitlement](https://developer.apple.com/documentation/BundleResources/Entitlements/com.apple.developer.networking.networkextension)
- [App Groups entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.application-groups)

## گواهی توسعه

یک گواهی **Apple Development** همراه با کلید خصوصی آن در Keychain مک یا از طریق Xcode ایجاد کنید و آن را به‌صورت فایل رمزدار `.p12` صادر کنید. رمز P12 را نگه دارید. همان گواهی باید در هر دو provisioning profile انتخاب شده باشد.

سه فایل باید آماده باشند:
1. گواهی Apple Development با کلید خصوصی: `.p12`
2. پروفایل iOS App Development برای `com.raahat.vpn`
3. پروفایل iOS App Development برای `com.raahat.vpn.packet-tunnel`

## ساخت فایل Secret در Windows

فایل `tools/prepare-ios-signing.cmd` را با دوبار کلیک اجرا کنید. ابزار:
- Team ID را می‌گیرد.
- سه فایل بالا را با پنجرهٔ انتخاب فایل دریافت می‌کند.
- رمز P12 را به‌صورت امن از ورودی مخفی می‌گیرد.
- فایل `github-secrets.txt` را در پوشهٔ Documents\RaahatVPN-iOS-Signing می‌سازد.
- دسترسی پوشه و فایل را به حساب فعلی Windows و SYSTEM محدود می‌کند.

این فایل حاوی کلید خصوصی رمزگذاری‌شده و رمز آن است؛ آن را در GitHub repository، چت یا فضای عمومی آپلود نکنید. فقط مقدار هر Secret را در تنظیمات مخزن وارد کنید. پس از ثبت موفق Secretها، فایل را در محل امن نگه دارید یا حذف کنید.

## ثبت Secretها در GitHub

در مخزن [idan8005/OneTapVPN](https://github.com/idan8005/OneTapVPN) به مسیر زیر بروید:

**Settings → Secrets and variables → Actions → New repository secret**

پنج Secret زیر را جداگانه بسازید و Value هر کدام را از فایل `github-secrets.txt` کپی کنید:

| نام Secret | مقدار |
|---|---|
| `RAAHAT_IOS_TEAM_ID` | Team ID حساب Apple Developer |
| `RAAHAT_IOS_CERTIFICATE_P12_BASE64` | مقدار Base64 گواهی P12 |
| `RAAHAT_IOS_CERTIFICATE_P12_PASSWORD` | رمز گواهی P12 |
| `RAAHAT_IOS_APP_PROFILE_BASE64` | مقدار Base64 پروفایل برنامه |
| `RAAHAT_IOS_EXTENSION_PROFILE_BASE64` | مقدار Base64 پروفایل افزونه |

هیچ‌یک از این مقادیر را در کد یا فایل‌های مخزن commit نکنید. Workflow فقط Secretها را می‌خواند، کلید موقت را روی runner ایجاد می‌کند و پس از ساخت، Keychain موقت را پاک می‌کند.

## ساخت IPA

پس از ثبت هر پنج Secret:

1. در GitHub وارد زبانهٔ **Actions** شوید.
2. Workflow با نام **Raahat VPN — Signed iOS IPA** را باز کنید.
3. **Run workflow** را بزنید.
4. در اجرای موفق، Artifact با نام `RaahatVPN-iOS-Development-IPA` ظاهر می‌شود.

فایل IPA توسعه فقط روی دستگاه‌هایی نصب می‌شود که داخل provisioning profile ثبت شده‌اند. می‌توان آن را با Xcode Devices and Simulators یا Apple Configurator روی همان دستگاه مجاز نصب کرد.

## نکات مهم

- برنامه و افزونه دو Bundle ID و دو provisioning profile مستقل دارند.
- App Group باید در هر دو پروفایل موجود باشد.
- entitlement مربوط به Packet Tunnel باید در پروفایل افزونه مجاز باشد.
- این Workflow برای **Development IPA** است؛ خروجی App Store یا TestFlight نیست.
- امضای موفق فقط نصب‌پذیری روی دستگاه ثبت‌شده را آماده می‌کند؛ عملکرد VPN، مسیر Full Tunnel و نشت DNS/IPv6 هنوز باید روی iPhone واقعی آزمایش شوند.
- اگر Apple حساب را برای Network Extensions مجاز نکرده باشد یا provisioning profile entitlement لازم را نداشته باشد، Workflow عمداً متوقف می‌شود.
