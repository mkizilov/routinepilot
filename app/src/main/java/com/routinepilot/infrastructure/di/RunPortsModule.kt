package com.routinepilot.infrastructure.di

import com.routinepilot.domain.execution.PausedRunReminders
import com.routinepilot.domain.execution.RunForegroundLauncher
import com.routinepilot.infrastructure.notifications.ServiceRunForegroundLauncher
import com.routinepilot.infrastructure.work.WorkManagerRunReminders
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RunPortsModule {
    @Binds
    abstract fun bindPausedRunReminders(impl: WorkManagerRunReminders): PausedRunReminders

    @Binds
    abstract fun bindRunForegroundLauncher(impl: ServiceRunForegroundLauncher): RunForegroundLauncher
}
