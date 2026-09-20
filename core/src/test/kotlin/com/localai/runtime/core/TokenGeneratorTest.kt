package com.localai.runtime.core

import com.localai.runtime.core.security.TokenGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [TokenGenerator] (SecureRandom tokens + six-digit pairing codes).
 */
class TokenGeneratorTest {

    @Test
    fun tokensAreUniqueAcross100Draws() {
        val tokens = (1..100).map { TokenGenerator.randomToken() }.toSet()
        assertEquals(100, tokens.size)
    }

    @Test
    fun tokensUseDefaultPrefixAndStableShape() {
        repeat(50) {
            val token = TokenGenerator.randomToken()
            assertTrue("missing lai_ prefix: $token", token.startsWith("lai_"))
            assertEquals("lai_".length + 64, token.length)
            assertTrue("suffix not 64 hex chars: $token", token.substring(4).matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun customPrefixIsHonoured() {
        repeat(10) {
            val token = TokenGenerator.randomToken(prefix = "dev-")
            assertTrue(token.startsWith("dev-"))
            assertEquals("dev-".length + 64, token.length)
            assertTrue(token.substring(4).matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun sixDigitCodeShape() {
        repeat(100) {
            val code = TokenGenerator.sixDigitCode()
            assertTrue("not six digits: $code", code.matches(Regex("\\d{6}")))
        }
    }

    @Test
    fun sixDigitCodesAreWellSpread() {
        // 100 draws over the 1e6 code space: exact uniqueness is not guaranteed
        // (birthday collisions ~0.5%), so assert a well-spread sample instead.
        val codes = (1..100).map { TokenGenerator.sixDigitCode() }.toSet()
        assertTrue("only ${codes.size} distinct codes in 100 draws", codes.size >= 95)
    }
}
