package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

@Dao
interface RoutineStepDao {
    @Query("SELECT * FROM routine_steps WHERE routineId = :routineId AND deletedAt IS NULL ORDER BY position, id")
    fun observeActiveForRoutine(routineId: UUID): Flow<List<RoutineStepEntity>>

    @Query("SELECT * FROM routine_steps WHERE routineId = :routineId AND deletedAt IS NULL ORDER BY position, id")
    suspend fun activeSnapshot(routineId: UUID): List<RoutineStepEntity>

    @Query("SELECT id FROM routine_steps WHERE routineId = :routineId AND deletedAt IS NULL")
    suspend fun activeIds(routineId: UUID): List<UUID>

    @Query("SELECT * FROM routine_steps WHERE id = :id AND deletedAt IS NULL")
    suspend fun findActive(id: UUID): RoutineStepEntity?

    /** Steps removed by the same routine deletion, which a restore brings back. */
    @Query("SELECT * FROM routine_steps WHERE routineId = :routineId AND deletedAt = :deletedAt")
    suspend fun deletedSnapshot(routineId: UUID, deletedAt: Instant): List<RoutineStepEntity>

    @Upsert
    suspend fun upsert(steps: List<RoutineStepEntity>)

    @Query("UPDATE routine_steps SET deletedAt = :at, modifiedAt = :at WHERE id = :id")
    suspend fun markDeleted(id: UUID, at: Instant)

    @Query(
        "UPDATE routine_steps SET deletedAt = :at, modifiedAt = :at " +
            "WHERE routineId = :routineId AND deletedAt IS NULL",
    )
    suspend fun markDeletedForRoutine(routineId: UUID, at: Instant)

    /** Restores only steps tombstoned by the same routine deletion, leaving earlier step deletions intact. */
    @Query(
        "UPDATE routine_steps SET deletedAt = NULL, modifiedAt = :at " +
            "WHERE routineId = :routineId AND deletedAt = :deletedAt",
    )
    suspend fun restoreDeletedAt(routineId: UUID, deletedAt: Instant, at: Instant)

    /** Hard delete, used only by sync purge. Fails while completion events reference the step. */
    @Query("DELETE FROM routine_steps WHERE id = :id")
    suspend fun purge(id: UUID)
}
