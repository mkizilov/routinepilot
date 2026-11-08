package com.routinepilot.domain.model

import java.time.Instant
import java.util.UUID

data class Task(
    val id: UUID,
    val title: String,
    val isCompleted: Boolean,
    val routineId: UUID?,
    val position: Int,
    val createdAt: Instant,
)
