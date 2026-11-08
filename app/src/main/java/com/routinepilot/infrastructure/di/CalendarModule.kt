package com.routinepilot.infrastructure.di

import com.routinepilot.domain.calendar.CalendarGateway
import com.routinepilot.infrastructure.calendar.ProviderCalendarGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarModule {
    @Binds
    abstract fun bindCalendarGateway(impl: ProviderCalendarGateway): CalendarGateway
}
