package com.localai.runtime.core

import com.localai.runtime.core.util.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Pure JVM tests for [Hashing] (byte-array + streamed file SHA-256, constant-time equality).
 */
class HashingTest {

    @Test
    fun sha256OfWellKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun sha256OfEmptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hashing.sha256(ByteArray(0)),
        )
    }

    @Test
    fun sha256OutputIsLowercaseHex() {
        val hex = Hashing.sha256("LocalAI".toByteArray())
        assertEquals(64, hex.length)
        assertTrue(hex.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun fileHashingMatchesInMemoryHashing() {
        val dir = createTempDirectory(prefix = "hashing-test").toFile()
        try {
            val payload = ByteArray(300_000) { (it % 251).toByte() } // spans several 64 KB chunks
            val file = File(dir, "payload.bin")
            file.writeBytes(payload)

            assertEquals(Hashing.sha256(payload), Hashing.sha256(file))
            assertEquals(payload.size.toLong(), file.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fileHashingReportsProgressInBytes() {
        val dir = createTempDirectory(prefix = "hashing-test").toFile()
        try {
            val payload = ByteArray(200_000) { (it % 7).toByte() }
            val file = File(dir, "progress.bin")
            file.writeBytes(payload)

            var seen = 0L
            var callbackCount = 0
            val digest = Hashing.sha256(file) { processed ->
                seen = processed
                callbackCount++
            }

            assertEquals(payload.size.toLong(), seen)
            assertTrue("expected chunked callbacks, got $callbackCount", callbackCount >= 1)
            assertEquals(Hashing.sha256(payload), digest)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun constantTimeEqualsAcceptsIdenticalStrings() {
        assertTrue(Hashing.constantTimeEquals("lai_token", "lai_token"))
        assertTrue(Hashing.constantTimeEquals("", ""))
        assertTrue(Hashing.constantTimeEquals("üñïçø∂é", "üñïçø∂é"))
    }

    @Test
    fun constantTimeEqualsRejectsDifferentStrings() {
        assertFalse(Hashing.constantTimeEquals("lai_token", "lai_tokeN"))
        assertFalse(Hashing.constantTimeEquals("lai_token", "lai_toke"))
        assertFalse(Hashing.constantTimeEquals("lai_token", "lai_token2"))
        assertFalse(Hashing.constantTimeEquals("abc", ""))
        assertFalse(Hashing.constantTimeEquals("", "abc"))
    }
}
