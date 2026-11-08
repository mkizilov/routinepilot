package com.routinepilot.infrastructure.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.routinepilot.domain.repository.ScheduleRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Keeps the planning horizon rolling and re-anchors schedules after a time-zone change. Generation is idempotent,
 * so a late or repeated run changes nothing that is already planned.
 */
@HiltWorker
class ScheduleMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduleRepository: ScheduleRepository,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        scheduleRepository.ensureOccurrences(Instant.now(clock), clock.zone)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "schedule-maintenance"
        private const val REPEAT_HOURS = 12L

        /** KEEP leaves an existing job in place, so repeated app starts never stack a second one. */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleMaintenanceWorker>(REPEAT_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
