package com.routinepilot.domain.model

import java.time.Instant
import java.util.UUID

enum class OccurrenceStatus { PLANNED, COMPLETED, SKIPPED }

data class ScheduledOccurrence(
    val id: UUID,
    val routineId: UUID,
    val scheduledAt: Instant,
    val status: OccurrenceStatus,
    val createdAt: Instant,
)
