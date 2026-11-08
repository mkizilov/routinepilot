package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines WHERE deletedAt IS NULL ORDER BY position, id")
    fun observeActive(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE id = :id AND deletedAt IS NULL")
    fun observeActiveById(id: UUID): Flow<RoutineEntity?>

    @Query("SELECT * FROM routines WHERE id = :id AND deletedAt IS NULL")
    suspend fun findActive(id: UUID): RoutineEntity?

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun findIncludingDeleted(id: UUID): RoutineEntity?

    @Query("SELECT COUNT(*) FROM routines WHERE deletedAt IS NULL")
    suspend fun countActive(): Int

    @Upsert
    suspend fun upsert(routine: RoutineEntity)

    @Query("UPDATE routines SET deletedAt = :at, modifiedAt = :at WHERE id = :id")
    suspend fun markDeleted(id: UUID, at: Instant)

    @Query("UPDATE routines SET deletedAt = NULL, modifiedAt = :at WHERE id = :id")
    suspend fun restore(id: UUID, at: Instant)

    /** Hard delete, used only by sync purge after tombstones are no longer needed. */
    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun purge(id: UUID)
}
