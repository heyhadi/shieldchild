package com.shieldchild.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TotpTest {
    // RFC 6238 Appendix B, SHA1 secret "12345678901234567890".
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test
    fun matchesRfc6238TestVectors() {
        val vectors = mapOf(
            59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471",
            1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130"
        )
        vectors.forEach { (seconds, expected) ->
            assertEquals(expected, Totp.code(rfcSecret, Totp.step(seconds * 1000), digits = 8))
        }
        // The 6-digit code Google Authenticator shows is the last 6 digits.
        assertEquals("287082", Totp.code(rfcSecret, Totp.step(59_000)))
    }

    @Test
    fun acceptsOneStepEitherSideAndRejectsReplay() {
        val now = 1_700_000_000_000L
        val step = Totp.step(now)
        val previous = Totp.code(rfcSecret, step - 1)
        assertEquals(step - 1, Totp.verify(rfcSecret, previous, now, lastUsedStep = 0))
        assertNull(Totp.verify(rfcSecret, previous, now, lastUsedStep = step - 1)) // already used
        assertNull(Totp.verify(rfcSecret, Totp.code(rfcSecret, step - 2), now, 0)) // too old
        assertNull(Totp.verify(rfcSecret, "12345", now, 0))
    }

    @Test
    fun base32MatchesRfc4648() {
        assertEquals("MZXW6YTBOI", Totp.base32("foobar".toByteArray()))
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(rfcSecret))
    }
}
