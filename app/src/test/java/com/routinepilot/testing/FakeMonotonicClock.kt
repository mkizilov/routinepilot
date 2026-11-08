package com.routinepilot.testing

import com.routinepilot.domain.execution.MonotonicClock

/** A monotonic clock that only moves when a test moves it. */
class FakeMonotonicClock(
    private var nowMillis: Long = 100_000L,
) : MonotonicClock {
    override fun elapsedMillis(): Long = nowMillis

    fun advance(millis: Long) {
        require(millis >= 0) { "monotonic time cannot move backwards" }
        nowMillis += millis
    }
}
