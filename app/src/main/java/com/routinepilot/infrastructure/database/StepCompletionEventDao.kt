package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface StepCompletionEventDao {
    @Query(
        "SELECT * FROM step_completion_events WHERE sessionId = :sessionId AND deletedAt IS NULL " +
            "ORDER BY completedAt, id",
    )
    fun observeActiveForSession(sessionId: UUID): Flow<List<StepCompletionEventEntity>>

    @Upsert
    suspend fun upsert(event: StepCompletionEventEntity)
}
