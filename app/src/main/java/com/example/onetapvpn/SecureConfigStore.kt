package com.example.onetapvpn

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

/** Stores WireGuard profiles encrypted with an AES-GCM key protected by Android Keystore. */
internal class SecureConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val securePreferences = appContext.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun read(): String? {
        val encrypted = securePreferences.getString(KEY_PAYLOAD, null)
        val iv = securePreferences.getString(KEY_IV, null)
        if (encrypted.isNullOrBlank() || iv.isNullOrBlank()) {
            return migrateLegacyProfile()
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
            )
            val plaintext = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
            String(plaintext, StandardCharsets.UTF_8)
        } catch (error: Exception) {
            securePreferences.edit().clear().commit()
            throw IllegalStateException(
                "The saved VPN profile could not be decrypted. Import the profile again.",
                error
            )
        }
    }

    @Synchronized
    fun write(profile: String) {
        require(profile.isNotBlank()) { "The VPN profile is empty." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = Base64.encodeToString(
            cipher.doFinal(profile.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        check(
            securePreferences.edit()
                .putString(KEY_PAYLOAD, encrypted)
                .putString(KEY_IV, iv)
                .commit()
        ) { "Could not save the encrypted VPN profile." }
    }

    @Synchronized
    fun clear() {
        securePreferences.edit().clear().commit()
        appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(LEGACY_CONFIG_KEY)
            .commit()
    }

    private fun migrateLegacyProfile(): String? {
        val legacyPreferences = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacyProfile = legacyPreferences.getString(LEGACY_CONFIG_KEY, null)?.takeIf { it.isNotBlank() }
            ?: return null

        write(legacyProfile)
        check(legacyPreferences.edit().remove(LEGACY_CONFIG_KEY).commit()) {
            "Could not remove the old unencrypted VPN profile."
        }
        return legacyProfile
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "onetapvpn.wireguard.profile.v1"
        const val SECURE_PREFS = "onetapvpn_secure_profile"
        const val LEGACY_PREFS = "vpn"
        const val KEY_PAYLOAD = "profile_ciphertext"
        const val KEY_IV = "profile_iv"
        const val LEGACY_CONFIG_KEY = "config"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
