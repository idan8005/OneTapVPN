package com.raahat.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnSecurityStatusTest {
    @Test
    fun protectionIsReadyOnlyWhenBothSystemOptionsAreEnabled() {
        val status = VpnSecurityStatus(
            supported = true,
            alwaysOnEnabled = true,
            lockdownEnabled = true
        )

        assertTrue(status.protectionReady)
        assertTrue(status.summary.contains("هر دو گزینه"))
    }

    @Test
    fun alwaysOnWithoutLockdownIsNotReportedAsFullProtection() {
        val status = VpnSecurityStatus(
            supported = true,
            alwaysOnEnabled = true,
            lockdownEnabled = false
        )

        assertFalse(status.protectionReady)
        assertTrue(status.summary.contains("مسدودسازی"))
    }

    @Test
    fun lockdownWithoutAlwaysOnIsNotReportedAsFullProtection() {
        val status = VpnSecurityStatus(
            supported = true,
            alwaysOnEnabled = false,
            lockdownEnabled = true
        )

        assertFalse(status.protectionReady)
        assertTrue(status.summary.contains("Always-on"))
    }

    @Test
    fun unsupportedAndroidVersionIsNotReportedAsDisabled() {
        val status = VpnSecurityStatus(
            supported = false,
            alwaysOnEnabled = false,
            lockdownEnabled = false
        )

        assertFalse(status.protectionReady)
        assertTrue(status.summary.contains("Android 10"))
    }
}
