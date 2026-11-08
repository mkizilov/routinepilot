package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.routinepilot.domain.model.OccurrenceStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One planned or finished instance. Occurrences belong to a routine or a task, or to a schedule. A null schedule
 * marks a one-off occurrence. The unique key on (scheduleId, originalScheduledAt) is the database-level guard
 * against duplicates: a slot can exist only once per schedule.
 */
@Entity(
    tableName = "scheduled_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["scheduleId", "originalScheduledAt"],
            name = "index_scheduled_occurrences_scheduleId_originalScheduledAt",
            unique = true,
        ),
        Index(value = ["routineId"], name = "index_scheduled_occurrences_routineId"),
        Index(value = ["taskId"], name = "index_scheduled_occurrences_taskId"),
        Index(value = ["scheduledAt"], name = "index_scheduled_occurrences_scheduledAt"),
        Index(value = ["localDate"], name = "index_scheduled_occurrences_localDate"),
    ],
)
data class ScheduledOccurrenceEntity(
    @PrimaryKey val id: UUID,
    val scheduleId: UUID?,
    val routineId: UUID?,
    val taskId: UUID?,
    val originalScheduledAt: Instant,
    val scheduledAt: Instant,
    val localDate: LocalDate,
    val status: OccurrenceStatus,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val deletedAt: Instant? = null,
)
