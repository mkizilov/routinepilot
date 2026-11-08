package com.routinepilot.infrastructure.di

import android.content.Context
import androidx.room.Room
import com.routinepilot.infrastructure.database.RoutineDao
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RoutinePilotDatabase =
        Room.databaseBuilder(context, RoutinePilotDatabase::class.java, "routinepilot.db").build()

    @Provides
    fun provideRoutineDao(database: RoutinePilotDatabase): RoutineDao = database.routineDao()
}
