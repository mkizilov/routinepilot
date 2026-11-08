package com.routinepilot.domain.execution

/** Monotonic time in milliseconds. Only differences between readings are meaningful. */
interface MonotonicClock {
    fun elapsedMillis(): Long
}
