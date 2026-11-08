package com.routinepilot.infrastructure.di

import com.routinepilot.data.planning.PlanningRepositoryImpl
import com.routinepilot.domain.repository.PlanningRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlanningModule {
    @Binds
    abstract fun bindPlanningRepository(impl: PlanningRepositoryImpl): PlanningRepository
}
