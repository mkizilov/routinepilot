package com.routinepilot.infrastructure.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.routinepilot.infrastructure.widget.WidgetRefresher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Brings widgets up to date when the app process is not alive, for example after a reboot or a process kill. Only a
 * periodic job can do this without an exact alarm. Android may run it later than planned, and it may skip runs when
 * the device is idle.
 */
@HiltWorker
class WidgetRefreshWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val refresher: WidgetRefresher,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            refresher.refreshAll()
            return Result.success()
        }

        companion object {
            const val UNIQUE_NAME = "widget-refresh"
            private const val REPEAT_MINUTES = 30L

            /** KEEP leaves an existing job alone, so repeated app starts never stack a second job. */
            fun enqueue(context: Context) {
                val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(REPEAT_MINUTES, TimeUnit.MINUTES).build()
                WorkManager
                    .getInstance(context)
                    .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
