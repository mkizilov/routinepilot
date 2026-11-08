package com.routinepilot.infrastructure.di

import com.routinepilot.data.calendar.CalendarRepositoryImpl
import com.routinepilot.data.execution.DataStoreActiveExecutionStore
import com.routinepilot.data.repository.ExecutionRepositoryImpl
import com.routinepilot.data.repository.PreferenceRepositoryImpl
import com.routinepilot.data.repository.RoutineRepositoryImpl
import com.routinepilot.data.repository.ScheduleRepositoryImpl
import com.routinepilot.data.repository.StatsRepositoryImpl
import com.routinepilot.data.repository.TaskRepositoryImpl
import com.routinepilot.domain.execution.ActiveExecutionStore
import com.routinepilot.domain.repository.CalendarRepository
import com.routinepilot.domain.repository.ExecutionRepository
import com.routinepilot.domain.repository.PreferenceRepository
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.domain.repository.ScheduleRepository
import com.routinepilot.domain.repository.StatsRepository
import com.routinepilot.domain.repository.TaskRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindRoutineRepository(impl: RoutineRepositoryImpl): RoutineRepository

    @Binds
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    abstract fun bindScheduleRepository(impl: ScheduleRepositoryImpl): ScheduleRepository

    @Binds
    abstract fun bindExecutionRepository(impl: ExecutionRepositoryImpl): ExecutionRepository

    @Binds
    abstract fun bindPreferenceRepository(impl: PreferenceRepositoryImpl): PreferenceRepository

    @Binds
    abstract fun bindActiveExecutionStore(impl: DataStoreActiveExecutionStore): ActiveExecutionStore

    @Binds
    abstract fun bindStatsRepository(impl: StatsRepositoryImpl): StatsRepository

    @Binds
    abstract fun bindCalendarRepository(impl: CalendarRepositoryImpl): CalendarRepository
}
