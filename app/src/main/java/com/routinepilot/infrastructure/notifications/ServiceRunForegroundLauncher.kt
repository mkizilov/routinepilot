package com.routinepilot.infrastructure.notifications

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.routinepilot.domain.execution.RunForegroundLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ServiceRunForegroundLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : RunForegroundLauncher {
    private companion object {
        const val TAG = "RoutineRunService"
    }

    /**
     * Android throws an IllegalStateException (ForegroundServiceStartNotAllowedException) when a foreground start
     * is refused. That is reported as false so the run screen can say timing may stop in the background. The run
     * itself continues in-process and is still checkpointed.
     */
    override fun ensureRunning(): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, RoutineRunService::class.java))
        true
    } catch (refused: IllegalStateException) {
        Log.w(TAG, "Foreground run service start refused; timing continues in-process", refused)
        false
    }
}
