package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.routinepilot.domain.model.SessionOutcome
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity(
    tableName = "execution_sessions",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScheduledOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrenceId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["routineId"], name = "index_execution_sessions_routineId"),
        Index(value = ["occurrenceId"], name = "index_execution_sessions_occurrenceId"),
        Index(value = ["localDate"], name = "index_execution_sessions_localDate"),
    ],
)
data class ExecutionSessionEntity(
    @PrimaryKey val id: UUID,
    val routineId: UUID,
    val occurrenceId: UUID?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val deletedAt: Instant? = null,
    val outcome: SessionOutcome? = null,
    val focusedMillis: Long? = null,
    val localDate: LocalDate? = null,
)
