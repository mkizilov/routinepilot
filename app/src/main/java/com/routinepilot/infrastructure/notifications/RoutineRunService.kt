package com.routinepilot.infrastructure.notifications

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.routinepilot.data.execution.RoutineRunCoordinator
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.execution.RunSnapshot
import com.routinepilot.infrastructure.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps a RUNNING routine alive in the background, with a countdown notification.
 *
 * It is the only reason timing survives a backgrounded app. It exists only while a run is RUNNING: a pause or the
 * end of the run stops it, because a paused run needs no background work. Android allows the start only while the
 * app is in the foreground, and the run screen is the only place that starts it. [RoutineRunService] is not
 * sticky, so if the process dies the run comes back paused from its checkpoint.
 */
@AndroidEntryPoint
class RoutineRunService : Service() {
    @Inject lateinit var coordinator: RoutineRunCoordinator

    @Inject lateinit var presenter: RunNotificationPresenter

    @Inject @field:ApplicationScope lateinit var scope: CoroutineScope

    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            RUN_NOTIFICATION_ID,
            presenter.ongoing(coordinator.snapshot.value),
            foregroundServiceType(),
        )
        if (observer == null) {
            observer = scope.launch { observeRun() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        super.onDestroy()
    }

    private suspend fun observeRun() {
        var lastKey: Triple<ExecutionStatus, Int, String>? = null
        coordinator.snapshot.collect { snapshot ->
            val key = Triple(snapshot.timer.status, snapshot.timer.currentIndex, snapshot.routineTitle)
            if (key == lastKey) return@collect
            lastKey = key
            render(snapshot)
        }
    }

    private fun render(snapshot: RunSnapshot) {
        when (snapshot.timer.status) {
            ExecutionStatus.RUNNING -> presenter.post(RUN_NOTIFICATION_ID, presenter.ongoing(snapshot))
            ExecutionStatus.PAUSED -> {
                presenter.post(RUN_NOTIFICATION_ID, presenter.paused(snapshot))
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopRunService()
            }
            ExecutionStatus.NOT_STARTED, ExecutionStatus.COMPLETED, ExecutionStatus.CANCELLED -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                presenter.cancelRun()
                stopRunService()
            }
        }
    }

    private fun stopRunService() {
        observer?.cancel()
        stopSelf()
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
}
