package com.routinepilot.backend.security

import com.routinepilot.backend.config.RateRule
import com.routinepilot.backend.domain.RateLimitedException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    private var now = 1_000_000L
    private val limiter = FixedWindowRateLimiter { now }
    private val rule = RateRule(limit = 3, windowSeconds = 60)

    @Test
    fun allowsUpToTheLimit_thenDenies() {
        repeat(3) { assertTrue(limiter.tryAcquire("ip", rule).allowed) }

        val denied = limiter.tryAcquire("ip", rule)

        assertFalse(denied.allowed)
        assertEquals(60L, denied.retryAfterSeconds)
    }

    @Test
    fun retryAfter_countsDownWithinTheWindow() {
        repeat(3) { limiter.tryAcquire("ip", rule) }
        now += 15_000

        assertEquals(45L, limiter.tryAcquire("ip", rule).retryAfterSeconds)
    }

    @Test
    fun windowReopens_afterItsLength() {
        repeat(3) { limiter.tryAcquire("ip", rule) }
        now += 60_000

        assertTrue(limiter.tryAcquire("ip", rule).allowed)
    }

    @Test
    fun keysHaveSeparateBudgets() {
        repeat(3) { limiter.tryAcquire("a", rule) }

        assertTrue(limiter.tryAcquire("b", rule).allowed)
    }

    @Test
    fun enforce_throwsWithTheRetryTime() {
        repeat(3) { limiter.enforce("ip", rule) }

        val failure = assertFailsWith<RateLimitedException> { limiter.enforce("ip", rule) }

        assertEquals(60L, failure.retryAfterSeconds)
    }
}
