package com.routinepilot.domain.model

import java.time.Instant
import java.util.UUID

data class Routine(
    val id: UUID,
    val title: String,
    val position: Int,
    val createdAt: Instant,
    val description: String? = null,
)

data class RoutineStep(
    val id: UUID,
    val routineId: UUID,
    val title: String,
    val position: Int,
    val createdAt: Instant,
    val description: String? = null,
    val durationSeconds: Int? = null,
)
