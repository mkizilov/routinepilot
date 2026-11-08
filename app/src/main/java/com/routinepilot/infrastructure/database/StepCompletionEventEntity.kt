package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

@Entity(
    tableName = "step_completion_events",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RoutineStepEntity::class,
            parentColumns = ["id"],
            childColumns = ["stepId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sessionId"], name = "index_step_completion_events_sessionId"),
        Index(value = ["stepId"], name = "index_step_completion_events_stepId"),
    ],
)
data class StepCompletionEventEntity(
    @PrimaryKey val id: UUID,
    val sessionId: UUID,
    val stepId: UUID,
    val completedAt: Instant,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val deletedAt: Instant? = null,
)
