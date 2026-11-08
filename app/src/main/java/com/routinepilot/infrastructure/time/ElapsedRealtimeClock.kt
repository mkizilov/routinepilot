package com.routinepilot.infrastructure.time

import android.os.SystemClock
import com.routinepilot.domain.execution.MonotonicClock
import javax.inject.Inject

class ElapsedRealtimeClock @Inject constructor() : MonotonicClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}
