package com.routinepilot.data.mapping

import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.domain.model.ScheduledOccurrence
import com.routinepilot.domain.model.StepCompletionEvent
import com.routinepilot.domain.model.Task
import com.routinepilot.infrastructure.database.ExecutionSessionEntity
import com.routinepilot.infrastructure.database.RoutineEntity
import com.routinepilot.infrastructure.database.RoutineStepEntity
import com.routinepilot.infrastructure.database.ScheduledOccurrenceEntity
import com.routinepilot.infrastructure.database.StepCompletionEventEntity
import com.routinepilot.infrastructure.database.TaskEntity
import java.time.Instant

internal fun RoutineEntity.toDomain() = Routine(id, title, position, createdAt)

internal fun Routine.toEntity(modifiedAt: Instant) =
    RoutineEntity(id = id, title = title, position = position, createdAt = createdAt, modifiedAt = modifiedAt)

internal fun RoutineStepEntity.toDomain() = RoutineStep(id, routineId, title, position, createdAt)

internal fun RoutineStep.toEntity(modifiedAt: Instant) =
    RoutineStepEntity(
        id = id,
        routineId = routineId,
        title = title,
        position = position,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
    )

internal fun TaskEntity.toDomain() = Task(id, title, isCompleted, routineId, position, createdAt)

internal fun Task.toEntity(modifiedAt: Instant) =
    TaskEntity(
        id = id,
        title = title,
        isCompleted = isCompleted,
        routineId = routineId,
        position = position,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
    )

internal fun ScheduledOccurrenceEntity.toDomain() = ScheduledOccurrence(id, routineId, scheduledAt, status, createdAt)

internal fun ScheduledOccurrence.toEntity(modifiedAt: Instant) =
    ScheduledOccurrenceEntity(
        id = id,
        routineId = routineId,
        scheduledAt = scheduledAt,
        status = status,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
    )

internal fun ExecutionSessionEntity.toDomain() =
    ExecutionSession(id, routineId, occurrenceId, startedAt, endedAt, createdAt)

internal fun ExecutionSession.toEntity(modifiedAt: Instant) =
    ExecutionSessionEntity(
        id = id,
        routineId = routineId,
        occurrenceId = occurrenceId,
        startedAt = startedAt,
        endedAt = endedAt,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
    )

internal fun StepCompletionEventEntity.toDomain() = StepCompletionEvent(id, sessionId, stepId, completedAt, createdAt)

internal fun StepCompletionEvent.toEntity(modifiedAt: Instant) =
    StepCompletionEventEntity(
        id = id,
        sessionId = sessionId,
        stepId = stepId,
        completedAt = completedAt,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
    )
