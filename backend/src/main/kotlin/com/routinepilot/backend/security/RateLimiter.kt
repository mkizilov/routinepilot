package com.routinepilot.backend.security

import com.routinepilot.backend.config.RateRule
import com.routinepilot.backend.domain.RateLimitedException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-window counter per key, held in memory. It limits one process. A deployment with several instances needs a
 * shared store, as the backend README notes. [nowMillis] is injected so tests control time.
 */
class FixedWindowRateLimiter(private val nowMillis: () -> Long) {
    private class Window(val startMillis: Long, val windowMillis: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    fun tryAcquire(key: String, rule: RateRule): RateDecision {
        val now = nowMillis()
        val windowMillis = rule.windowSeconds * MILLIS_PER_SECOND
        var decision = RateDecision(allowed = true, retryAfterSeconds = 0)
        windows.compute(key) { _, current ->
            val window = if (current == null || now - current.startMillis >= windowMillis) {
                Window(now, windowMillis, 0)
            } else {
                current
            }
            if (window.count < rule.limit) {
                decision = RateDecision(allowed = true, retryAfterSeconds = 0)
                Window(window.startMillis, windowMillis, window.count + 1)
            } else {
                val remainingMillis = window.startMillis + windowMillis - now
                decision = RateDecision(
                    allowed = false,
                    retryAfterSeconds = (remainingMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND,
                )
                window
            }
        }
        if (windows.size > MAX_KEYS) prune(now)
        return decision
    }

    /** Throws when the key is over its limit, so route code reads as a single line. */
    fun enforce(key: String, rule: RateRule) {
        val decision = tryAcquire(key, rule)
        if (!decision.allowed) throw RateLimitedException(decision.retryAfterSeconds)
    }

    private fun prune(now: Long) {
        windows.entries.removeIf { now - it.value.startMillis >= it.value.windowMillis }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_KEYS = 100_000
    }
}

data class RateDecision(val allowed: Boolean, val retryAfterSeconds: Long)
