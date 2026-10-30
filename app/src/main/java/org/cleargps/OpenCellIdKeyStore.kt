package org.cleargps

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the user's personal OpenCellID API token with a non-exportable Android Keystore key.
 * The plaintext token is never written to SharedPreferences or diagnostic logs.
 */
internal object OpenCellIdKeyStore {
    private const val PREFS_NAME = "opencellid_credentials"
    private const val KEY_ENCRYPTED = "encrypted_api_key_v1"
    private const val KEY_ENABLED = "manual_lookup_enabled"
    private const val KEY_ALIAS = "org.cleargps.opencellid.api-key.v1"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun hasKey(context: Context): Boolean = read(context) != null

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun save(context: Context, candidate: String): Boolean {
        val token = candidate.trim()
        if (token.length !in 8..512 || token.any(Char::isWhitespace)) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + encrypted
            val value = "v1:" + Base64.encodeToString(packed, Base64.NO_WRAP)
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENCRYPTED, value).commit()
        } catch (_: Exception) {
            false
        }
    }

    fun read(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_ENCRYPTED, null) ?: return null
        if (!value.startsWith("v1:")) return null
        return try {
            val packed = Base64.decode(value.removePrefix("v1:"), Base64.NO_WRAP)
            if (packed.size <= GCM_IV_LENGTH) return null
            val iv = packed.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = packed.copyOfRange(GCM_IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_ENCRYPTED).putBoolean(KEY_ENABLED, false).commit()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
}
