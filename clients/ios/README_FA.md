# هسته مشترک iOS راحت وی‌پی‌ان

این Swift Package قرارداد API Hybrid و اعتبارسنجی VLESS + REALITY را برای کلاینت iOS فراهم می‌کند.

- درخواست‌های API فقط روی HTTPS انجام می‌شوند؛ redirect پذیرفته نمی‌شود.
- سرورهای Premium از حساب رایگان جدا هستند.
- parser پروفایل، UUID، SNI، کلید REALITY، short ID و transport را بررسی می‌کند.
- آزمون‌های Swift برای ساخت تنظیمات TUN و رد پروفایل‌های ناسازگار وجود دارد.

**محدودیت مهم:** این بسته هنوز اپ قابل نصب یا تونل فعال iOS نیست. مرحله بعد باید اپ SwiftUI و Packet Tunnel Extension را با sing-box Libbox ادغام کند. برای این کار Apple Network Extension با entitlement packet-tunnel-provider و امضای معتبر توسعه‌دهنده اپل ضروری است.

مستند Apple: https://developer.apple.com/documentation/networkextension/packet-tunnel-provider
موتور Apple sing-box: https://github.com/SagerNet/sing-box-for-apple
