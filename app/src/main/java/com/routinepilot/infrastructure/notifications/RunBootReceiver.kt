package com.routinepilot.infrastructure.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.routinepilot.data.execution.RunRecovery
import com.routinepilot.domain.execution.PausedRunReminders
import com.routinepilot.infrastructure.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A reboot ends the foreground service and resets the monotonic clock. A run that was RUNNING is paused at its last
 * checkpoint, the user is told, and a reminder is scheduled. WorkManager jobs survive the reboot on their own.
 */
@AndroidEntryPoint
class RunBootReceiver : BroadcastReceiver() {
    @Inject lateinit var recovery: RunRecovery

    @Inject lateinit var reminders: PausedRunReminders

    @Inject lateinit var presenter: RunNotificationPresenter

    @Inject @field:ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        scope.launch {
            try {
                recovery.pauseInterruptedRun()?.let { run ->
                    reminders.schedule(run.sessionId)
                    presenter.showPausedAfterRestart(run.routineId, run.routineTitle)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
