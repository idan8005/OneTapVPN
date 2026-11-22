package com.raahat.vpn

import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VpnConfigCipherTest {
    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun encryptsAndDecryptsConfigWithoutStoringPlaintext() {
        val config = """{"outbounds":[{"type":"vless","uuid":"example-secret"}]}"""
        val key = newKey()
        val blob = VpnConfigCipher.encrypt(config, key)
        assertEquals(config, VpnConfigCipher.decrypt(blob, key))
        assertNotEquals(config, String(blob.ciphertext, Charsets.UTF_8))
    }

    @Test
    fun usesFreshIvForEachEncryption() {
        val key = newKey()
        val first = VpnConfigCipher.encrypt("same config", key)
        val second = VpnConfigCipher.encrypt("same config", key)
        assertNotEquals(Base64.getEncoder().encodeToString(first.iv), Base64.getEncoder().encodeToString(second.iv))
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val key = newKey()
        val blob = VpnConfigCipher.encrypt("protected config", key)
        val changed = blob.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) {
            VpnConfigCipher.decrypt(EncryptedConfigBlob(blob.iv, changed), key)
        }
    }

    @Test
    fun rejectsBlankConfig() {
        assertThrows(IllegalArgumentException::class.java) {
            VpnConfigCipher.encrypt(" ", newKey())
        }
    }
}
