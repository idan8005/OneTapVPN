# کلاینت Windows راحت وی‌پی‌ان

کلاینت WPF روی .NET 10 با اتصال HTTPS به سرویس Hybrid و موتور sing-box رسمی Windows x64 نسخه 1.14.4 ساخته شده است.

## قابلیت‌های این مرحله
- دریافت کاتالوگ Premium و تأیید توکن حساب.
- رمزنگاری توکن با Windows DPAPI.
- دریافت موتور sing-box از مخزن رسمی و بررسی SHA-256 پیش از استخراج.
- ساخت تنظیمات TUN برای VLESS + REALITY و اعتبارسنجی با دستور sing-box check.
- آزمایش TCP سرورها و امکان انتخاب دستی یا خودکار.
- درخواست UAC فقط هنگام اتصال و تأیید عبور واقعی ترافیک اینترنت.

## ساخت
روی Windows با .NET 10 SDK:

```powershell
dotnet publish clients/windows/RaahatVPN.Desktop/RaahatVPN.Desktop.csproj -c Release -r win-x64 --self-contained true
```

نشانی HTTPS backend از متغیر محیطی RAAHAT_API_BASE_URL خوانده می‌شود. تا زمان استقرار backend و تنظیم نشانی آن، ورود و اتصال Premium فعال نمی‌شود.

## محدودیت
این مرحله فقط سرورهای Premium از نوع VLESS + REALITY را پشتیبانی می‌کند. دریافت سرورهای عمومی رایگان، OpenVPN، انتخاب برنامه‌ها، سرویس پس‌زمینه پایدار و kill switch مستقل هنوز باقی مانده‌اند. اتصال TUN به مجوز مدیر ویندوز نیاز دارد. تست ساخت CI جایگزین تست شبکه واقعی نیست.
