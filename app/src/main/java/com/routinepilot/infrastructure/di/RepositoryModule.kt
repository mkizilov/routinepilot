package com.routinepilot.infrastructure.di

import com.routinepilot.data.repository.RoutineRepositoryImpl
import com.routinepilot.domain.repository.RoutineRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindRoutineRepository(impl: RoutineRepositoryImpl): RoutineRepository
}
