# سرویس مرکزی راحت وی‌پی‌ان (Hybrid Control Plane)

این سرویس پایهٔ مدیریت کاتالوگ سرورهای اختصاصی و دسترسی رایگان/ویژه است. هنوز روی میزبان عمومی مستقر نشده و بدون سرور عملیاتی و secrets معتبر، قابلیت Premium برای کاربران فعال نیست.

## امکانات

- `GET /healthz`: بررسی سلامت.
- `GET /api/v1/catalog`: کاتالوگ با توکن کاربر.
- `PUT /admin/v1/servers`: مدیریت فهرست سرورهای Premium با توکن مدیر.
- `POST /admin/v1/tokens`: صدور توکن محدود به زمان.
- HMAC-SHA256 برای امضای توکن و AES-256-GCM برای رمزنگاری کاتالوگ در دیسک.
- ذخیرهٔ اتمی و محدودیت دسترسی فایل، اعتبارسنجی ورودی‌ها و هدرهای امنیتی.
- پروتکل‌های فعلی: `vless-reality` و `openvpn`؛ WireGuard پشتیبانی نمی‌شود.

## اجرا

پیش‌نیاز Go 1.23 یا Docker است. سه secret مستقل بسازید:

```bash
openssl rand -hex 32
openssl rand -hex 32
openssl rand -base64 32
```

مقادیر را به‌ترتیب در `RAAHAT_ADMIN_TOKEN`، `RAAHAT_TOKEN_SIGNING_KEY` و `RAAHAT_DATA_ENCRYPTION_KEY` قرار دهید. کلید رمزنگاری باید پس از Base64 decode دقیقاً ۳۲ بایت باشد.

```bash
cd backend
go test -race ./...
go vet ./...
go run .
```

سرویس باید در انتشار واقعی پشت HTTPS اجرا شود و مسیر داده روی volume دائمی قرار بگیرد. secretها فقط در محیط امن میزبان نگهداری شوند.

## API

### صدور دسترسی

`POST /admin/v1/tokens` با هدر `Authorization: Bearer <ADMIN_TOKEN>` و بدنه:

```json
{"userId":"user-100","plan":"premium","ttlSeconds":2592000}
```

plan فقط `free` یا `premium` است و طول عمر توکن بین ۶۰ ثانیه تا ۳۶۶ روز محدود شده است. این endpoint مدیریتی اولیه است؛ هنوز به ثبت‌نام یا درگاه پرداخت وصل نیست.

### ثبت سرورهای Premium

`PUT /admin/v1/servers` با هدر مدیر و بدنه‌ای مانند:

```json
{"servers":[{"id":"eu-01","name":"Europe 01","country":"Germany","region":"EU","host":"vpn.example.net","port":443,"protocol":"vless-reality","config":"<connection-profile>","enabled":true,"maxUsers":100,"priority":10}]}
```

این فقط قالب نمونه است، نه سرور واقعی. اطلاعات اتصال محرمانه‌اند؛ از اپراتور سرورهای تحت مالکیت یا مدیریت خود دریافت کنید.

### کاتالوگ

`GET /api/v1/catalog` با توکن کاربر. حساب رایگان کاتالوگ Premium خالی و `publicDiscovery: true` دریافت می‌کند؛ حساب Premium فقط سرورهای فعال مدیریت‌شده را می‌گیرد. پاسخ `Cache-Control: no-store` دارد.

## کارهای باقی‌مانده

- اتصال این API به کلاینت Android و پیاده‌سازی ورود/حساب کاربری.
- ساخت کلاینت‌های Windows و iOS.
- پنل گرافیکی مدیریت، سیستم پرداخت، تمدید و ابطال دسترسی.
- استقرار واقعی، محدودکردن دسترسی مدیریتی به شبکهٔ امن و آزمون نفوذ.
- تأمین سرورهای Premium واقعی و پایش ۲۴/۷.

این پایه به‌تنهایی سرویس تجاری فعال یا سرور اختصاصی ایجاد نمی‌کند.
