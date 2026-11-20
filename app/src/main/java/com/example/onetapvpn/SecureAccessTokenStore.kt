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

/**
 * Stores the short-lived account access token encrypted with a non-exportable
 * AES key held by Android Keystore. Tokens are never written to logs.
 */
internal class SecureAccessTokenStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun saveToken(token: String) {
        val value = token.trim()
        require(value.isNotEmpty() && value.length <= MAX_TOKEN_LENGTH) {
            "The access token is empty or too long."
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        ) { "Could not securely save the account token." }
    }

    @Synchronized
    fun readToken(): String? {
        val ivText = preferences.getString(KEY_IV, null) ?: return null
        val encryptedText = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        return try {
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val encrypted = Base64.decode(encryptedText, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            clearToken()
            null
        }
    }

    @Synchronized
    fun clearToken() {
        preferences.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "raahat_secure_account"
        const val KEY_IV = "access_token_iv"
        const val KEY_CIPHERTEXT = "access_token_ciphertext"
        const val KEY_ALIAS = "raahat.hybrid.access-token.v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val MAX_TOKEN_LENGTH = 4096
    }
}
