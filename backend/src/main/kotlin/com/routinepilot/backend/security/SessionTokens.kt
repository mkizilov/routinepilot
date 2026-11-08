package com.routinepilot.backend.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object SessionTokens {
    private const val PREFIX = "rp_"
    private const val RANDOM_BYTES = 32
    private val random = SecureRandom()

    /** 256 bits of randomness, so guessing a token is not feasible. */
    fun generate(): String {
        val bytes = ByteArray(RANDOM_BYTES).also { random.nextBytes(it) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Tokens are random, not chosen, so a fast hash is enough. A database leak then does not reveal usable tokens.
     */
    fun hash(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
