package com.routinepilot.infrastructure.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules sync passes. Every pass waits for a connection. A pass that could not reach the server is retried with
 * exponential backoff, starting at [BACKOFF_SECONDS]. Local work never waits on any of this.
 */
@Singleton
class SyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * APPEND_OR_REPLACE means a change made while a pass is running gets its own pass, instead of being left
         * for the
         * periodic job.
         */
        fun requestNow() {
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(connected())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(ONE_TIME_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** KEEP leaves an existing periodic job in place, so repeated sign-ins never stack a second one. */
        fun startPeriodic() {
            val request =
                PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
                    .setConstraints(connected())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelAll() {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(ONE_TIME_NAME)
                cancelUniqueWork(PERIODIC_NAME)
            }
        }

        private fun connected(): Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        companion object {
            internal const val ONE_TIME_NAME = "routinepilot-sync"
            internal const val PERIODIC_NAME = "routinepilot-sync-periodic"
            private const val PERIODIC_HOURS = 6L
            private const val BACKOFF_SECONDS = 30L
        }
    }
