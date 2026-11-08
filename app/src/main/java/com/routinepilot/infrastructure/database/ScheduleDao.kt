package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE deletedAt IS NULL ORDER BY createdAt, id")
    fun observeActive(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE id = :id AND deletedAt IS NULL")
    fun observeActiveById(id: UUID): Flow<ScheduleEntity?>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun findById(id: UUID): ScheduleEntity?

    /** Active schedules whose routine or task is still active. Generation skips the rest. */
    @Query(
        "SELECT s.* FROM schedules AS s " +
            "LEFT JOIN routines AS r ON s.routineId = r.id " +
            "LEFT JOIN tasks AS t ON s.taskId = t.id " +
            "WHERE s.deletedAt IS NULL AND " +
            "((s.routineId IS NOT NULL AND r.deletedAt IS NULL) OR (s.taskId IS NOT NULL AND t.deletedAt IS NULL))",
    )
    suspend fun activeForGeneration(): List<ScheduleEntity>

    @Upsert
    suspend fun upsert(schedule: ScheduleEntity)

    @Query("UPDATE schedules SET zoneId = :zone WHERE id = :id")
    suspend fun updateZone(id: UUID, zone: ZoneId)

    @Query("UPDATE schedules SET deletedAt = :at, modifiedAt = :at WHERE id = :id")
    suspend fun markDeleted(id: UUID, at: Instant)
}
