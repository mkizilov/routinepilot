package com.routinepilot.infrastructure.di

import com.routinepilot.domain.execution.MonotonicClock
import com.routinepilot.infrastructure.time.ElapsedRealtimeClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TimeModule {
    @Binds
    abstract fun bindMonotonicClock(impl: ElapsedRealtimeClock): MonotonicClock

    companion object {
        @Provides
        @Singleton
        fun provideWallClock(): Clock = Clock.systemUTC()
    }
}
