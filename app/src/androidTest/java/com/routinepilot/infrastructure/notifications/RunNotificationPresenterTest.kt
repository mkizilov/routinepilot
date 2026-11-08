package com.routinepilot.infrastructure.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.execution.RunSnapshot
import com.routinepilot.domain.execution.TimedStep
import com.routinepilot.domain.execution.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class RunNotificationPresenterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val presenter = RunNotificationPresenter(context)

    @Test
    fun createChannels_registersAQuietOngoingChannelAndAnEventsChannel() {
        presenter.createChannels()

        val manager = context.getSystemService(NotificationManager::class.java)
        val ongoing = manager.getNotificationChannel("routine_run_ongoing")
        assertNotNull(ongoing)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ongoing.importance)
        assertNotNull(manager.getNotificationChannel("routine_run_events"))
    }

    @Test
    fun ongoing_isNotDismissable_countsDownWithTheSystemChronometer_andHasPauseAndSkip() {
        val notification = presenter.ongoing(runningSnapshot(remainingMillis = 60_000L))

        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(2, notification.actions.size)
        assertNotNull(notification.contentIntent)
        val expectedEnd = System.currentTimeMillis() + 60_000L
        assertTrue(abs(notification.`when` - expectedEnd) < 5_000L)
    }

    @Test
    fun ongoing_forAnUntimedStep_hasNoCountdown() {
        val notification = presenter.ongoing(runningSnapshot(remainingMillis = null))

        assertEquals(2, notification.actions.size)
        assertTrue(abs(notification.`when` - System.currentTimeMillis()) < 5_000L)
    }

    @Test
    fun paused_isDismissable_andOffersResumeAndFinish() {
        val notification = presenter.paused(runningSnapshot(remainingMillis = 30_000L))

        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(2, notification.actions.size)
        assertNotNull(notification.contentIntent)
    }

    private fun runningSnapshot(remainingMillis: Long?) = RunSnapshot(
        routineId = UUID.randomUUID(),
        routineTitle = "Morning",
        timer = TimerState(
            status = ExecutionStatus.RUNNING,
            steps = listOf(TimedStep(UUID.randomUUID(), "Stretch", if (remainingMillis == null) null else 60)),
            currentIndex = 0,
            currentElapsedMillis = 0L,
            currentRemainingMillis = remainingMillis,
            totalRemainingMillis = remainingMillis ?: 0L,
        ),
        isLoading = false,
    )
}
