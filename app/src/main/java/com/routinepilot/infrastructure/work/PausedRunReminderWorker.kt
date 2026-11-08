package com.routinepilot.infrastructure.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.routinepilot.data.execution.RunRecovery
import com.routinepilot.infrastructure.notifications.RunNotificationPresenter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

/**
 * Deferrable reminder that a run is still paused. It may run late, which is fine for a reminder. It stays quiet when
 * the run has resumed, finished, or been replaced.
 */
@HiltWorker
class PausedRunReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recovery: RunRecovery,
    private val presenter: RunNotificationPresenter,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Result.failure()
        recovery.pausedRunForReminder(sessionId)?.let { run ->
            presenter.showPausedReminder(run.routineId, run.routineTitle)
        }
        return Result.success()
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
    }
}
