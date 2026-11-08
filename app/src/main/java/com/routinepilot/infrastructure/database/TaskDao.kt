package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE deletedAt IS NULL ORDER BY position, id")
    fun observeActive(): Flow<List<TaskEntity>>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("UPDATE tasks SET deletedAt = :at, modifiedAt = :at WHERE id = :id")
    suspend fun markDeleted(id: UUID, at: Instant)
}
