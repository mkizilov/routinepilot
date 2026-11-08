package com.routinepilot.backend.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2-HMAC-SHA256 from the JDK, so no native library is needed. The stored form records the algorithm and the
 * iteration count, so the cost can be raised later without breaking existing hashes. Verification compares in constant
 * time.
 */
class PasswordHasher(private val iterations: Int) {
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val key = derive(password, salt, iterations, KEY_BITS)
        return listOf(ALGORITHM_ID, iterations.toString(), ENCODER.encodeToString(salt), ENCODER.encodeToString(key))
            .joinToString(SEPARATOR)
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != ALGORITHM_ID) return false
        val rounds = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { DECODER.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { DECODER.decode(parts[3]) }.getOrNull() ?: return false
        val actual = derive(password, salt, rounds, expected.size * 8)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: String, salt: ByteArray, rounds: Int, bits: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, bits)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val ALGORITHM_ID = "pbkdf2_sha256"
        const val SEPARATOR = "\$"
        const val SALT_BYTES = 16
        const val KEY_BITS = 256
        val ENCODER: Base64.Encoder = Base64.getEncoder()
        val DECODER: Base64.Decoder = Base64.getDecoder()
    }
}
