package com.localai.runtime.core.security

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * File-backed secret storage. Each secret lives as one encrypted file under
 * `context.noBackupFilesDir/secrets/<name>.bin` containing
 * [KeystoreCipher.encrypt] output (IV || ciphertext), so secrets never
 * participate in Android backups and never appear in plaintext on disk.
 *
 * Note for callers: these are small synchronous file operations by contract
 * (non-suspend API); invoke from a background dispatcher when in doubt.
 */
class SecretStore(
    private val context: Context,
    private val cipher: KeystoreCipher = KeystoreCipher(),
) {

    /** Persists [token] encrypted at rest. Throws [IllegalStateException] on failure. */
    fun saveToken(name: String, token: String) {
        try {
            val dir = secretsDir()
            if (!dir.isDirectory) dir.mkdirs()
            File(dir, fileName(name)).writeBytes(cipher.encrypt(token))
        } catch (e: IllegalStateException) {
            throw e
        } catch (t: Throwable) {
            throw IllegalStateException("Failed to store secret '$name'", t)
        }
    }

    /** Reads and decrypts the stored token, or null when absent/unreadable/corrupt. */
    fun readToken(name: String): String? {
        return try {
            val file = File(secretsDir(), fileName(name))
            if (!file.isFile) null else cipher.decrypt(file.readBytes())
        } catch (_: Throwable) {
            null
        }
    }

    /** Best-effort removal; never throws. */
    fun clearToken(name: String) {
        try {
            File(secretsDir(), fileName(name)).delete()
        } catch (_: Throwable) {
            // best-effort
        }
    }

    /** Lowercase hex SHA-256 of the *decrypted* token, or null when not stored. */
    fun tokenSha256(name: String): String? = readToken(name)?.let { sha256(it) }

    private fun secretsDir(): File = File(context.noBackupFilesDir, "secrets")

    private fun fileName(name: String): String = sanitize(name) + ".bin"

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        /** Lowercase hex SHA-256 of a string's UTF-8 bytes. */
        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                hex.append("0123456789abcdef"[v ushr 4])
                hex.append("0123456789abcdef"[v and 0x0F])
            }
            return hex.toString()
        }
    }
}
