package com.localai.runtime.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256/GCM encryption backed by the AndroidKeyStore.
 *
 * Wire format: 12-byte random IV prepended to the ciphertext.
 * The key never leaves secure hardware where available; there is no
 * password and no exportable key material.
 */
class KeystoreCipher(private val keyAlias: String = "localai_master") {

    /** Encrypts [plain] (UTF-8) into `IV || ciphertext+tag`. */
    fun encrypt(plain: String): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv // 12 random bytes for AndroidKeyStore GCM
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            iv + ciphertext
        } catch (e: Exception) {
            throw IllegalStateException("Keystore encryption failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** Splits the leading 12-byte IV from [blob] and decrypts the remainder. */
    fun decrypt(blob: ByteArray): String {
        if (blob.size <= IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Encrypted blob too short (${blob.size} bytes)")
        }
        return try {
            val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("Keystore decryption failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** Best-effort removal of the key entry; never throws. */
    fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        } catch (_: Throwable) {
            // best-effort by contract
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_LENGTH_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val KEY_LENGTH_BITS = 256
    }
}
