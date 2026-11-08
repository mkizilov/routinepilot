package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.routinepilot.domain.model.OccurrenceStatus
import java.time.Instant
import java.util.UUID

@Entity(
    tableName = "scheduled_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["routineId", "scheduledAt"], name = "index_scheduled_occurrences_routineId_scheduledAt"),
    ],
)
data class ScheduledOccurrenceEntity(
    @PrimaryKey val id: UUID,
    val routineId: UUID,
    val scheduledAt: Instant,
    val status: OccurrenceStatus,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val deletedAt: Instant? = null,
)
