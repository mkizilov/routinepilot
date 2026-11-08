package com.routinepilot.backend.security

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionTokensTest {
    @Test
    fun generate_producesPrefixedUrlSafeTokensThatDiffer() {
        val first = SessionTokens.generate()
        val second = SessionTokens.generate()

        assertTrue(first.startsWith("rp_"))
        assertTrue(first.matches(Regex("rp_[A-Za-z0-9_-]{43}")), first)
        assertNotEquals(first, second)
    }

    @Test
    fun hash_isStableHexAndHidesTheToken() {
        val token = SessionTokens.generate()

        assertEquals(SessionTokens.hash(token), SessionTokens.hash(token))
        assertTrue(SessionTokens.hash(token).matches(Regex("[0-9a-f]{64}")))
        assertFalse(SessionTokens.hash(token).contains(token))
        assertNotEquals(SessionTokens.hash(token), SessionTokens.hash(token + "x"))
    }
}
