package com.localai.runtime.core.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Hashing helpers (pure JVM, no Android dependencies — unit-testable).
 *
 * All hex output is lowercase.
 */
object Hashing {

    private const val FILE_CHUNK_BYTES = 64 * 1024
    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Streams [file] through SHA-256 in 64 KB chunks on [Dispatchers.IO].
     * [onProgress] is invoked with the number of bytes processed so far
     * after every chunk (throttled to chunk granularity, not per byte).
     *
     * @throws java.io.IOException when the file cannot be read.
     */
    suspend fun sha256(file: File, onProgress: (Long) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(FILE_CHUNK_BYTES)
            var processed = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    digest.update(buffer, 0, read)
                    processed += read
                    onProgress(processed)
                }
            }
        }
        toHexLower(digest.digest())
    }

    /** SHA-256 of an in-memory byte array as lowercase hex. */
    fun sha256(bytes: ByteArray): String =
        toHexLower(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Constant-time string equality on UTF-8 bytes via
     * [MessageDigest.isEqual]; safe for token comparison.
     */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun toHexLower(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }
}
