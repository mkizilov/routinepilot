package com.routinepilot.infrastructure.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RoutineEntity::class,
        RoutineStepEntity::class,
        TaskEntity::class,
        ScheduledOccurrenceEntity::class,
        ExecutionSessionEntity::class,
        StepCompletionEventEntity::class,
        UserPreferenceEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class RoutinePilotDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao

    abstract fun routineStepDao(): RoutineStepDao

    abstract fun taskDao(): TaskDao

    abstract fun scheduledOccurrenceDao(): ScheduledOccurrenceDao

    abstract fun executionSessionDao(): ExecutionSessionDao

    abstract fun stepCompletionEventDao(): StepCompletionEventDao

    abstract fun userPreferenceDao(): UserPreferenceDao
}
