package com.routinepilot.infrastructure.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scheduling rules for sync passes: a pass waits for a connection, a periodic pass is never duplicated, and
 * sign-out cancels both. Uses WorkManager's test setup, so the passes do not actually run.
 */
class SyncSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = SyncScheduler(context)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun requestNow_queuesAPass_thatStaysWaitingWithoutAConnection() {
        scheduler.requestNow()

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.ONE_TIME_NAME).get()
        assertTrue(infos.isNotEmpty())
        assertEquals(WorkInfo.State.ENQUEUED, infos.last().state)
    }

    @Test
    fun startPeriodic_calledTwice_keepsOneSchedule() {
        scheduler.startPeriodic()
        scheduler.startPeriodic()

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_NAME).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun cancelAll_cancelsBothPasses() {
        scheduler.requestNow()
        scheduler.startPeriodic()
        scheduler.cancelAll()

        val manager = WorkManager.getInstance(context)
        val infos = manager.getWorkInfosForUniqueWork(SyncScheduler.ONE_TIME_NAME).get() +
            manager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_NAME).get()
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }
}
