package com.routinepilot

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.routinepilot.infrastructure.notifications.RunEventNotifier
import com.routinepilot.infrastructure.notifications.RunNotificationPresenter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RoutinePilotApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var presenter: RunNotificationPresenter

    @Inject lateinit var runEvents: RunEventNotifier

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        presenter.createChannels()
        runEvents.start()
    }
}
