package com.routinepilot.infrastructure.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.routinepilot.domain.execution.PausedRunReminders
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Uses WorkManager rather than an exact alarm: the reminder can be late, WorkManager keeps it across process death
 * and reboot, and no exact-alarm permission is needed.
 */
class WorkManagerRunReminders
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PausedRunReminders {
        override fun schedule(sessionId: UUID) {
            val request =
                OneTimeWorkRequestBuilder<PausedRunReminderWorker>()
                    .setInitialDelay(REMINDER_DELAY_MINUTES, TimeUnit.MINUTES)
                    .setInputData(workDataOf(PausedRunReminderWorker.KEY_SESSION_ID to sessionId.toString()))
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        override fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        private companion object {
            const val UNIQUE_NAME = "paused-run-reminder"
            const val REMINDER_DELAY_MINUTES = 15L
        }
    }
