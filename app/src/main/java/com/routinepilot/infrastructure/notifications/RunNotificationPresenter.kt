package com.routinepilot.infrastructure.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.routinepilot.R
import com.routinepilot.domain.execution.RunEvent
import com.routinepilot.domain.execution.RunSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

const val RUN_NOTIFICATION_ID = 1001
const val EVENT_NOTIFICATION_ID = 1002

private const val CHANNEL_ONGOING = "routine_run_ongoing"
private const val CHANNEL_EVENTS = "routine_run_events"
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Builds and posts the run notifications. The ongoing notification uses the system chronometer, so it counts down
 * without the app updating it every second. It is only refreshed when the step or run state changes.
 */
@Singleton
class RunNotificationPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                context.getString(R.string.notification_channel_ongoing_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notification_channel_ongoing_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                context.getString(R.string.notification_channel_events_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_events_description) },
        )
    }

    /** Also used as the foreground service notification, so it is built even when notifications are off. */
    fun ongoing(snapshot: RunSnapshot): Notification {
        val timer = snapshot.timer
        val builder = baseBuilder(CHANNEL_ONGOING)
            .setContentTitle(timer.currentStep?.title ?: snapshot.routineTitle)
            .setContentText(progressText(snapshot))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .addAction(
                R.drawable.ic_notification,
                text(R.string.notification_action_pause),
                RunIntents.command(context, RunIntents.ACTION_PAUSE),
            )
            .addAction(
                R.drawable.ic_notification,
                text(R.string.notification_action_skip),
                RunIntents.command(context, RunIntents.ACTION_SKIP),
            )
        snapshot.routineId?.let { builder.setContentIntent(RunIntents.openRun(context, it, resume = false)) }
        val remaining = timer.currentRemainingMillis
        if (remaining != null) {
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() + remaining)
        }
        return builder.build()
    }

    fun paused(snapshot: RunSnapshot): Notification = pausedNotification(snapshot.routineId, snapshot.routineTitle)

    fun showStepCompleted(event: RunEvent.StepCompleted) {
        post(
            EVENT_NOTIFICATION_ID,
            baseBuilder(CHANNEL_EVENTS)
                .setContentTitle(text(R.string.notification_step_done_title, event.stepTitle))
                .setContentText(text(R.string.notification_step_next_text, event.nextStepTitle))
                .setAutoCancel(true)
                .setContentIntent(RunIntents.openRun(context, event.routineId, resume = false))
                .build(),
        )
    }

    fun showRoutineFinished(event: RunEvent.RoutineFinished) {
        post(
            EVENT_NOTIFICATION_ID,
            baseBuilder(CHANNEL_EVENTS)
                .setContentTitle(text(R.string.notification_finished_title, event.routineTitle))
                .setContentText(text(R.string.notification_finished_text))
                .setAutoCancel(true)
                .setContentIntent(RunIntents.openRun(context, event.routineId, resume = false))
                .build(),
        )
    }

    /** Replaces the paused notification, so there is only one run notification at a time. */
    fun showPausedReminder(routineId: UUID, routineTitle: String) {
        post(
            RUN_NOTIFICATION_ID,
            pausedNotification(
                routineId,
                routineTitle,
                text(R.string.notification_reminder_title, routineTitle),
                text(R.string.notification_reminder_text),
            ),
        )
    }

    fun showPausedAfterRestart(routineId: UUID, routineTitle: String) {
        post(
            RUN_NOTIFICATION_ID,
            pausedNotification(
                routineId,
                routineTitle,
                text(R.string.notification_restart_title),
                text(R.string.notification_restart_text, routineTitle),
            ),
        )
    }

    fun cancelRun() {
        NotificationManagerCompat.from(context).cancel(RUN_NOTIFICATION_ID)
    }

    private fun pausedNotification(
        routineId: UUID?,
        routineTitle: String,
        title: String = routineTitle,
        body: String = text(R.string.notification_paused_text),
    ): Notification {
        val builder = baseBuilder(CHANNEL_EVENTS)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(false)
            .setAutoCancel(false)
            .addAction(
                R.drawable.ic_notification,
                text(R.string.notification_action_finish),
                RunIntents.command(context, RunIntents.ACTION_FINISH),
            )
        if (routineId != null) {
            builder.setContentIntent(RunIntents.openRun(context, routineId, resume = false))
            builder.addAction(
                R.drawable.ic_notification,
                text(R.string.notification_action_resume),
                RunIntents.openRun(context, routineId, resume = true),
            )
        }
        return builder.build()
    }

    private fun progressText(snapshot: RunSnapshot): String {
        val timer = snapshot.timer
        val total = DateUtils.formatElapsedTime(timer.totalRemainingMillis / MILLIS_PER_SECOND)
        return text(R.string.notification_run_progress, timer.currentIndex + 1, timer.steps.size, total)
    }

    private fun baseBuilder(channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId).setSmallIcon(R.drawable.ic_notification)

    /** Posts only when the user allows notifications. The foreground service keeps running either way. */
    @SuppressLint("MissingPermission")
    fun post(id: Int, notification: Notification) {
        if (NotificationAccess.canPost(context)) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)
}
