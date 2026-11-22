package com.raahat.vpn

import java.net.Inet6Address
import java.net.InetAddress

internal enum class ProbeStatus {
    PASSED,
    FAILED,
    UNAVAILABLE
}

internal data class TunnelHealthReport(
    val dns: ProbeStatus,
    val ipv4: ProbeStatus,
    val ipv6: ProbeStatus
) {
    val isReady: Boolean
        get() = dns == ProbeStatus.PASSED && ipv4 == ProbeStatus.PASSED

    fun toPersianMessage(): String {
        val dnsText = when (dns) {
            ProbeStatus.PASSED -> "تأیید"
            else -> "ناموفق"
        }
        val ipv4Text = when (ipv4) {
            ProbeStatus.PASSED -> "تأیید"
            else -> "ناموفق"
        }
        val ipv6Text = when (ipv6) {
            ProbeStatus.PASSED ->
                "پاسخ داد؛ این نتیجه به‌تنهایی نبود نشت IPv6 را ثابت نمی‌کند"
            else ->
                "پاسخ نداد؛ ممکن است سرور IPv6 را پشتیبانی نکند"
        }

        return if (isReady) {
            "اتصال تأیید شد؛ DNS: $dnsText، IPv4: $ipv4Text، IPv6: $ipv6Text."
        } else {
            "تأیید اتصال ناموفق بود؛ DNS: $dnsText، IPv4: $ipv4Text، IPv6: $ipv6Text."
        }
    }
}

/**
 * Evaluates only the result of tunnel-side probes. The IPv6 result is informational:
 * reachability alone cannot prove whether IPv6 travelled through the VPN or leaked outside it.
 */
internal object TunnelHealthPolicy {
    fun evaluate(
        dnsResolved: Boolean,
        ipv4Address: String?,
        ipv6Address: String?
    ): TunnelHealthReport = TunnelHealthReport(
        dns = if (dnsResolved) ProbeStatus.PASSED else ProbeStatus.FAILED,
        ipv4 = if (isPublicIpv4(ipv4Address)) ProbeStatus.PASSED else ProbeStatus.FAILED,
        ipv6 = if (isPublicIpv6(ipv6Address)) ProbeStatus.PASSED else ProbeStatus.UNAVAILABLE
    )

    internal fun isPublicIpv4(value: String?): Boolean {
        val parts = value?.trim()?.split('.')?.map { it.toIntOrNull() ?: return false }
            ?: return false
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false

        val a = parts[0]
        val b = parts[1]
        val c = parts[2]
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 192 && b == 0 && c in setOf(0, 2) -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            else -> true
        }
    }

    internal fun isPublicIpv6(value: String?): Boolean {
        val candidate = value?.trim()?.takeIf { it.contains(':') } ?: return false
        val address = runCatching { InetAddress.getByName(candidate) as? Inet6Address }.getOrNull()
            ?: return false
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
        ) return false

        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        if ((first and 0xe0) != 0x20) return false // Global unicast space (2000::/3) only.
        if (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) return false // Documentation prefix.
        return true
    }
}
