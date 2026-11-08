package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY id")
    fun observeAll(): Flow<List<RoutineEntity>>
}
