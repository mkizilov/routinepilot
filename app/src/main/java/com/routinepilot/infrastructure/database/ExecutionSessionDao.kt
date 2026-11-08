package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

@Dao
interface ExecutionSessionDao {
    @Query(
        "SELECT * FROM execution_sessions WHERE routineId = :routineId AND deletedAt IS NULL " +
            "ORDER BY startedAt DESC, id",
    )
    fun observeActiveForRoutine(routineId: UUID): Flow<List<ExecutionSessionEntity>>

    @Query("SELECT * FROM execution_sessions WHERE id = :id")
    suspend fun findById(id: UUID): ExecutionSessionEntity?

    @Upsert
    suspend fun upsert(session: ExecutionSessionEntity)

    @Query("UPDATE execution_sessions SET deletedAt = :at, modifiedAt = :at WHERE id = :id")
    suspend fun markDeleted(
        id: UUID,
        at: Instant,
    )
}
