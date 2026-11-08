package com.routinepilot.infrastructure.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.routinepilot.data.execution.RoutineRunCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles pause, skip, and finish from the run notifications. Not exported: only our own PendingIntents reach it.
 * The coordinator adopts the stored run first if this process was started only for the action.
 */
@AndroidEntryPoint
class RunActionReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: RoutineRunCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RunIntents.ACTION_PAUSE -> coordinator.pause()
            RunIntents.ACTION_SKIP -> coordinator.skip()
            RunIntents.ACTION_FINISH -> coordinator.finish()
        }
    }
}
