package com.routinepilot.backend.security

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordHasherTest {
    private val hasher = PasswordHasher(iterations = 10_000)

    @Test
    fun verify_acceptsTheRightPassword() {
        val stored = hasher.hash("correct horse battery")

        assertTrue(hasher.verify("correct horse battery", stored))
    }

    @Test
    fun verify_rejectsAWrongPassword() {
        val stored = hasher.hash("correct horse battery")

        assertFalse(hasher.verify("correct horse batter", stored))
        assertFalse(hasher.verify("", stored))
    }

    @Test
    fun hash_saltsEachHash_soEqualPasswordsStoreDifferently() {
        assertNotEquals(hasher.hash("same password here"), hasher.hash("same password here"))
    }

    @Test
    fun hash_recordsTheAlgorithmAndCost() {
        val stored = hasher.hash("correct horse battery")

        assertTrue(stored.startsWith("pbkdf2_sha256\$10000\$"), stored)
        assertFalse(stored.contains("correct horse battery"))
    }

    @Test
    fun verify_usesTheCostStoredWithTheHash_soTheCostCanBeRaisedLater() {
        val stored = PasswordHasher(iterations = 20_000).hash("correct horse battery")

        assertTrue(hasher.verify("correct horse battery", stored))
    }

    @Test
    fun verify_rejectsMalformedOrForeignHashes() {
        assertFalse(hasher.verify("anything", "not-a-hash"))
        assertFalse(hasher.verify("anything", "bcrypt\$10\$abc\$def"))
        assertFalse(hasher.verify("anything", "pbkdf2_sha256\$notanumber\$AAAA\$AAAA"))
        assertFalse(hasher.verify("anything", "pbkdf2_sha256\$10000\$!!!\$!!!"))
    }
}
