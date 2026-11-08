package com.routinepilot.testing

import com.routinepilot.domain.execution.PausedRunReminders
import com.routinepilot.domain.execution.RunForegroundLauncher
import java.util.UUID

/** Records reminder requests instead of scheduling WorkManager jobs. */
class FakePausedRunReminders : PausedRunReminders {
    val scheduled = mutableListOf<UUID>()
    var cancelCount = 0
        private set

    override fun schedule(sessionId: UUID) {
        scheduled += sessionId
    }

    override fun cancel() {
        cancelCount++
    }
}

/** Counts foreground requests. [accepts] simulates the platform refusing a background start. */
class FakeRunForegroundLauncher(
    var accepts: Boolean = true,
) : RunForegroundLauncher {
    var requests = 0
        private set

    override fun ensureRunning(): Boolean {
        requests++
        return accepts
    }
}
