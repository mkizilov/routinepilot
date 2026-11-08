package com.routinepilot

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.routinepilot.data.execution.RoutineRunCoordinator
import com.routinepilot.data.sync.SyncObserver
import com.routinepilot.infrastructure.calendar.CalendarSyncObserver
import com.routinepilot.infrastructure.notifications.RunEventNotifier
import com.routinepilot.infrastructure.notifications.RunNotificationPresenter
import com.routinepilot.infrastructure.widget.WidgetRefresher
import com.routinepilot.infrastructure.work.ScheduleMaintenanceWorker
import com.routinepilot.infrastructure.work.WidgetRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RoutinePilotApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var presenter: RunNotificationPresenter

    @Inject lateinit var runEvents: RunEventNotifier

    @Inject lateinit var coordinator: RoutineRunCoordinator

    @Inject lateinit var widgets: WidgetRefresher

    @Inject lateinit var calendarSync: CalendarSyncObserver

    @Inject lateinit var syncObserver: SyncObserver

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        presenter.createChannels()
        runEvents.start()
        ScheduleMaintenanceWorker.enqueue(this)
        coordinator.reconcileStoredRun()
        widgets.start()
        WidgetRefreshWorker.enqueue(this)
        calendarSync.start()
        syncObserver.start()
    }
}
