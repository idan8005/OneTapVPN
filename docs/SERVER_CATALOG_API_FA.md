# API کاتالوگ سرورهای Raahat VPN

## مسیرهای عمومی

- `GET /healthz`
- `GET /api/v1/health`
- `GET /api/v1/servers`
- `GET /api/v1/servers?tier=free`
- `GET /api/v1/servers?tier=premium`

`/api/v1/servers` فقط سرورهای فعال و سالم را برمی‌گرداند. رکوردهای منبع VPN Gate نیز فقط وقتی منتشر می‌شوند که بررسی TCP آن‌ها موفق شده و سطح تأیید `tcp-reachable` باشد. این بررسی جای دست‌دهی VPN یا تست عبور ترافیک را نمی‌گیرد.

## تنظیمات اجرا

| متغیر | کاربرد |
|---|---|
| `RAAHAT_LISTEN_ADDR` | آدرس listen صریح؛ در صورت تنظیم، بر بقیه اولویت دارد |
| `PORT` | پورت اختصاص‌یافته توسط میزبان؛ در صورت نبود `RAAHAT_LISTEN_ADDR`، برنامه روی `0.0.0.0:<PORT>` گوش می‌دهد |
| `RAAHAT_TLS_CERT_FILE` و `RAAHAT_TLS_KEY_FILE` | گواهی و کلید TLS مستقیم؛ هر دو باید با هم تنظیم شوند |
| `RAAHAT_TLS_TERMINATED_BY_PROXY=true` | فقط وقتی TLS در پراکسی معتمد جلوی API خاتمه می‌یابد |
| `RAAHAT_CORS_ALLOWED_ORIGINS` | originهای مجاز، جداشده با کاما؛ مقدار پیش‌فرض `*` برای API عمومیِ فقط‌خواندنی |
| `RAAHAT_VPNGATE_ENABLED=true` | دریافت فوری و سپس تازه‌سازی دوره‌ای کاتالوگ عمومی VPN Gate |
| `RAAHAT_CATALOG_FILE` | فایل JSON اولیه برای سرورهای مدیریت‌شده |

CORS فقط برای API عمومی و فقط‌خواندنی است. هیچ credential یا کوکی مرورگری لازم نیست؛ سرور `Access-Control-Allow-Credentials` را فعال نمی‌کند. برای دامنهٔ ثابت فرانت‌اند، origin دقیق را در `RAAHAT_CORS_ALLOWED_ORIGINS` قرار دهید.

## نکته‌های استقرار و پایداری

- هرگز HTTP بدون TLS را مستقیماً در اینترنت منتشر نکنید.
- اگر TLS در میزبان ابری خاتمه می‌یابد، فقط پس از اطمینان از اعتماد به آن پراکسی، `RAAHAT_TLS_TERMINATED_BY_PROXY=true` را فعال کنید.
- کاتالوگ فعلی در حافظه است. بعد از restart، دریافت‌کنندهٔ VPN Gate دوباره اجرا می‌شود؛ پایگاه دادهٔ پایدار در این مرحله وجود ندارد.
- API فقط فرادادهٔ عمومی سرور را منتشر می‌کند؛ پروفایل‌های OpenVPN، رمزها و کلیدهای خصوصی در پاسخ API قرار نمی‌گیرند.
- آزمون موفق TCP فقط به معنی پذیرش اتصال TCP به پورت مقصد در لحظهٔ بررسی است؛ کارکرد VPN باید داخل کلاینت و با آزمون ترافیک واقعی تأیید شود.

## آزمون

از پوشهٔ `backend`:

    go test -race ./...
    go vet ./...
    go build ./cmd/api
