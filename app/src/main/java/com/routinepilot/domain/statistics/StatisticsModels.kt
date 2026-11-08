package com.routinepilot.domain.statistics

import java.time.LocalDate
import java.util.UUID

/** Scheduled items on one day, by status. [missed] means still pending after its time has passed. */
data class OccurrenceTotals(val completed: Int, val skipped: Int, val missed: Int) {
    companion object {
        val NONE = OccurrenceTotals(0, 0, 0)
    }
}

/** Routine sessions that ended on one day. [focusedMillis] covers finished and cancelled sessions. */
data class SessionTotals(val finished: Int, val cancelled: Int, val focusedMillis: Long) {
    companion object {
        val NONE = SessionTotals(0, 0, 0L)
    }
}

/** Scheduled items of one routine over the consistency window. */
data class RoutineTotals(val routineId: UUID, val completed: Int, val missed: Int)

/** Raw aggregates from Room, keyed by device-zone date. Missing keys mean zero. */
data class StatisticsInput(
    val occurrences: Map<LocalDate, OccurrenceTotals>,
    val sessions: Map<LocalDate, SessionTotals>,
    val steps: Map<LocalDate, Int>,
    val routines: List<RoutineTotals>,
) {
    companion object {
        val EMPTY = StatisticsInput(emptyMap(), emptyMap(), emptyMap(), emptyList())
    }
}

/**
 * What a day means for the streak. KEPT: something was finished or completed. EXCUSED: only skipped items.
 * BROKEN: nothing was done and something was missed, or a past day had nothing done. IN_PROGRESS: today, nothing
 * missed yet. FUTURE: after today, never counted.
 */
enum class DayStatus { KEPT, EXCUSED, BROKEN, IN_PROGRESS, FUTURE }

data class DayStats(
    val date: LocalDate,
    val completed: Int,
    val skipped: Int,
    val missed: Int,
    val finishedSessions: Int,
    val cancelledSessions: Int,
    val completedSteps: Int,
    val focusedMillis: Long,
    val completionPercent: Int?,
    val status: DayStatus,
)

/** Totals over a span of days. [partial] is true when the span includes today or later. */
data class PeriodStats(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val completionPercent: Int?,
    val finishedSessions: Int,
    val completedSteps: Int,
    val focusedMillis: Long,
    val partial: Boolean,
)

data class RoutineConsistency(val routineId: UUID, val completed: Int, val missed: Int, val percent: Int?)

data class Statistics(
    val today: DayStats,
    val last7Days: PeriodStats,
    val weeks: List<PeriodStats>,
    val months: List<PeriodStats>,
    val calendar: List<DayStats>,
    val currentStreak: Int,
    val longestStreak: Int,
    val consistency: List<RoutineConsistency>,
)
