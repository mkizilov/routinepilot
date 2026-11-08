package com.routinepilot.domain.model

import java.time.Instant
import java.util.UUID

data class ExecutionSession(
    val id: UUID,
    val routineId: UUID,
    val occurrenceId: UUID?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val createdAt: Instant,
)

data class StepCompletionEvent(
    val id: UUID,
    val sessionId: UUID,
    val stepId: UUID,
    val completedAt: Instant,
    val createdAt: Instant,
)
