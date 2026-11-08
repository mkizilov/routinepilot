package com.routinepilot.domain.planning

import java.util.UUID

const val MINUTES_PER_DAY = 1_440

private const val MINUTES_PER_HOUR = 60

/** A span of minutes after midnight on the planned day, from [start] inclusive to [end] exclusive. */
data class TimeRange(
    val start: Int,
    val end: Int,
) {
    init {
        require(start >= 0 && start < end && end <= MINUTES_PER_DAY) { "Invalid time range $start-$end" }
    }

    val length: Int
        get() = end - start

    fun overlaps(other: TimeRange): Boolean = start < other.end && other.start < end
}

/** Something a flexible task must work around: an appointment, or a routine's start time with its length. */
data class FixedCommitment(
    val title: String,
    val range: TimeRange,
)

/**
 * A task to place on the planned day. [priority] runs from 1 (low) to 5 (high).
 * [deadlineMinute] is the latest minute the
 * task may finish. [dependsOn] lists tasks that must finish before this one starts.
 */
data class FlexibleTask(
    val id: UUID,
    val title: String,
    val durationMinutes: Int,
    val priority: Int,
    val deadlineMinute: Int?,
    val dependsOn: Set<UUID>,
) {
    init {
        require(durationMinutes > 0) { "A task needs a duration" }
        require(priority in MIN_PRIORITY..MAX_PRIORITY) { "Priority must be from $MIN_PRIORITY to $MAX_PRIORITY" }
        require(deadlineMinute == null || deadlineMinute in 1..MINUTES_PER_DAY) {
            "Deadline must be on the planned day"
        }
    }

    companion object {
        const val MIN_PRIORITY = 1
        const val MAX_PRIORITY = 5
    }
}

/**
 * Everything the planner needs for one day. [pins] are manual overrides: the start minute a user chose for a task.
 * They are never moved automatically. [previous] holds the starts of the last applied recommendations. A
 * recommendation keeps its
 * start while that start is still free and valid, so a plan stays steady when unrelated things change.
 */
data class PlanRequest(
    val workday: TimeRange,
    val breaks: List<TimeRange>,
    val commitments: List<FixedCommitment>,
    val tasks: List<FlexibleTask>,
    val pins: Map<UUID, Int> = emptyMap(),
    val previous: Map<UUID, Int> = emptyMap(),
    val granularityMinutes: Int = DEFAULT_GRANULARITY_MINUTES,
) {
    init {
        require(granularityMinutes > 0 && MINUTES_PER_HOUR % granularityMinutes == 0) {
            "Granularity must divide an hour"
        }
        require(workday.start % granularityMinutes == 0) { "The workday must start on the time grid" }
        require(tasks.map { it.id }.toSet().size == tasks.size) { "Task ids must be unique" }
    }

    companion object {
        const val DEFAULT_GRANULARITY_MINUTES = 15
    }
}
