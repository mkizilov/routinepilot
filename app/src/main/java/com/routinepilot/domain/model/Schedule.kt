package com.routinepilot.domain.model

import com.routinepilot.domain.schedule.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class OccurrenceStatus {
    PLANNED,
    RESCHEDULED,
    COMPLETED,
    SKIPPED,
    ;

    /** Still waiting to be done. Only pending occurrences can be completed, skipped, or moved. */
    val isPending: Boolean
        get() = this == PLANNED || this == RESCHEDULED
}

sealed interface ScheduleTarget {
    data class RoutineTarget(val routineId: UUID) : ScheduleTarget

    data class TaskTarget(val taskId: UUID) : ScheduleTarget
}

/**
 * A recurring rule for one routine or task. [zone] is the zone the rule was last anchored to. When the device
 * zone changes, pending occurrences are re-anchored to the new zone. [modifiedAt] is the earliest moment that
 * occurrences may be generated from, so editing a rule never creates items in the past.
 */
data class Schedule(
    val id: UUID,
    val target: ScheduleTarget,
    val recurrence: Recurrence,
    val zone: ZoneId,
    val createdAt: Instant,
    val modifiedAt: Instant,
)

/**
 * One planned instance of a schedule. [originalScheduledAt] identifies the slot the rule produced and never
 * changes, which is what prevents duplicates. [scheduledAt] moves when the occurrence is rescheduled.
 */
data class ScheduledOccurrence(
    val id: UUID,
    val scheduleId: UUID?,
    val routineId: UUID?,
    val taskId: UUID?,
    val originalScheduledAt: Instant,
    val scheduledAt: Instant,
    val localDate: LocalDate,
    val status: OccurrenceStatus,
    val createdAt: Instant,
    val deletedAt: Instant? = null,
)
