package com.localai.runtime.core.security

import java.security.SecureRandom

/**
 * Cryptographically secure token generator (pure JVM — unit-testable).
 */
object TokenGenerator {

    private val random = SecureRandom()
    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 32 random bytes from [SecureRandom], hex-encoded, prefixed:
     * `lai_` + 64 hex characters. Suitable for device pairing tokens.
     */
    fun randomToken(prefix: String = "lai_"): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            hex.append(HEX[v ushr 4])
            hex.append(HEX[v and 0x0F])
        }
        return prefix + hex
    }

    /** Six-digit numeric pairing code, e.g. "839221" (zero-padded, [SecureRandom]). */
    fun sixDigitCode(): String = String.format("%06d", random.nextInt(1_000_000))
}
