package com.routinepilot.infrastructure.di

import android.content.Context
import androidx.room.Room
import com.routinepilot.infrastructure.database.ForeignKeysCallback
import com.routinepilot.infrastructure.database.MIGRATION_1_2
import com.routinepilot.infrastructure.database.MIGRATION_2_3
import com.routinepilot.infrastructure.database.MIGRATION_3_4
import com.routinepilot.infrastructure.database.MIGRATION_4_5
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
        Room.databaseBuilder(context, RoutinePilotDatabase::class.java, "routinepilot.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .addCallback(ForeignKeysCallback)
            .build()
}
