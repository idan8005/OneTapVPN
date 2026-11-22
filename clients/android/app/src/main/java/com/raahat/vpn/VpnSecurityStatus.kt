package com.raahat.vpn

internal data class VpnSecurityStatus(
    val supported: Boolean,
    val alwaysOnEnabled: Boolean,
    val lockdownEnabled: Boolean
) {
    val protectionReady: Boolean
        get() = supported && alwaysOnEnabled && lockdownEnabled

    val alwaysOnLabel: String
        get() = if (alwaysOnEnabled) "فعال" else "خاموش"

    val lockdownLabel: String
        get() = if (lockdownEnabled) "فعال" else "خاموش"

    val summary: String
        get() = when {
            !supported -> "گزارش خودکار وضعیت در Android 10 و نسخه‌های جدیدتر در دسترس است."
            protectionReady -> "هر دو گزینهٔ محافظت سیستمی فعال هستند."
            alwaysOnEnabled -> "Always-on فعال است؛ برای Kill Switch کامل، مسدودسازی اتصال بدون VPN را هم روشن کن."
            lockdownEnabled -> "Lockdown گزارش شده، اما Always-on خاموش است؛ تنظیمات Android را بررسی کن."
            else -> "محافظت سیستمی Kill Switch فعال نیست."
        }
}
