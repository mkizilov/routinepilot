package com.routinepilot.infrastructure.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [RoutineEntity::class], version = 1, exportSchema = false)
abstract class RoutinePilotDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
}
