package com.raahat.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedConfigBlob(
    val iv: ByteArray,
    val ciphertext: ByteArray
)

/** AES-GCM helper kept independent from Android Keystore so the cipher is unit-testable. */
internal object VpnConfigCipher {
    fun encrypt(plaintext: String, key: SecretKey): EncryptedConfigBlob {
        require(plaintext.isNotBlank()) { "VPN config must not be blank." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return EncryptedConfigBlob(
            iv = cipher.iv,
            ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        )
    }

    fun decrypt(blob: EncryptedConfigBlob, key: SecretKey): String {
        require(blob.iv.size == GCM_IV_LENGTH_BYTES) { "Invalid encrypted config IV." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, blob.iv))
        return String(cipher.doFinal(blob.ciphertext), StandardCharsets.UTF_8)
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
}

/** Stores tunnel config only as ciphertext, with a non-exportable Android Keystore key. */
internal class EncryptedVpnConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun save(config: String) {
        require(config.isNotBlank() && config.length <= MAX_CONFIG_CHARS) {
            "VPN config is empty or exceeds the secure storage limit."
        }
        val blob = VpnConfigCipher.encrypt(config, getOrCreateKey())
        val saved = preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(blob.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(blob.ciphertext, Base64.NO_WRAP))
            .commit()
        check(saved) { "Could not persist encrypted VPN config." }
    }

    @Synchronized
    fun load(): String? {
        val ivText = preferences.getString(KEY_IV, null)
        val ciphertextText = preferences.getString(KEY_CIPHERTEXT, null)
        if (ivText == null && ciphertextText == null) return null
        if (ivText.isNullOrBlank() || ciphertextText.isNullOrBlank()) {
            clear()
            return null
        }
        return try {
            val blob = EncryptedConfigBlob(
                iv = Base64.decode(ivText, Base64.NO_WRAP),
                ciphertext = Base64.decode(ciphertextText, Base64.NO_WRAP)
            )
            VpnConfigCipher.decrypt(blob, getOrCreateKey())
                .takeIf { it.isNotBlank() && it.length <= MAX_CONFIG_CHARS }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES = "raahat_encrypted_vpn_config"
        private const val KEY_ALIAS = "raahat.vpn.config.aes_gcm.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val MAX_CONFIG_CHARS = 1_000_000
    }
}
