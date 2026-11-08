package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One row per device-zone day. Dates are ISO text, so BETWEEN compares them correctly and uses the index. */
data class OccurrenceDayRow(
    val day: String,
    val completed: Int,
    val skipped: Int,
    val missed: Int,
)

data class SessionDayRow(
    val day: String,
    val finished: Int,
    val cancelled: Int,
    val focusedMillis: Long,
)

data class StepDayRow(
    val day: String,
    val steps: Int,
)

data class RoutineTotalsRow(
    val routineId: String,
    val completed: Int,
    val missed: Int,
)

/**
 * Aggregates for statistics. Each query returns at most one row per day or routine, so results stay bounded by the
 * window rather than by how much history exists. Each one is a Flow, so Room re-runs it when its tables change.
 * "Missed" means still pending with a scheduled time before [nowMillis].
 */
@Dao
interface StatsDao {
    @Query(
        "SELECT localDate AS day, " +
            "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed, " +
            "SUM(CASE WHEN status = 'SKIPPED' THEN 1 ELSE 0 END) AS skipped, " +
            "SUM(CASE WHEN status IN ('PLANNED', 'RESCHEDULED') AND scheduledAt < :nowMillis THEN 1 ELSE 0 END) " +
            "AS missed FROM scheduled_occurrences " +
            "WHERE deletedAt IS NULL AND localDate BETWEEN :fromDay AND :toDay GROUP BY localDate",
    )
    fun occurrenceDays(
        fromDay: String,
        toDay: String,
        nowMillis: Long,
    ): Flow<List<OccurrenceDayRow>>

    @Query(
        "SELECT localDate AS day, " +
            "SUM(CASE WHEN outcome = 'FINISHED' THEN 1 ELSE 0 END) AS finished, " +
            "SUM(CASE WHEN outcome = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled, " +
            "SUM(CASE WHEN outcome IN ('FINISHED', 'CANCELLED') THEN COALESCE(focusedMillis, 0) ELSE 0 END) " +
            "AS focusedMillis FROM execution_sessions " +
            "WHERE deletedAt IS NULL AND endedAt IS NOT NULL AND localDate BETWEEN :fromDay AND :toDay " +
            "GROUP BY localDate",
    )
    fun sessionDays(
        fromDay: String,
        toDay: String,
    ): Flow<List<SessionDayRow>>

    @Query(
        "SELECT localDate AS day, COUNT(*) AS steps FROM step_completion_events " +
            "WHERE deletedAt IS NULL AND localDate BETWEEN :fromDay AND :toDay GROUP BY localDate",
    )
    fun stepDays(
        fromDay: String,
        toDay: String,
    ): Flow<List<StepDayRow>>

    @Query(
        "SELECT routineId, " +
            "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed, " +
            "SUM(CASE WHEN status IN ('PLANNED', 'RESCHEDULED') AND scheduledAt < :nowMillis THEN 1 ELSE 0 END) " +
            "AS missed FROM scheduled_occurrences " +
            "WHERE deletedAt IS NULL AND routineId IS NOT NULL AND localDate BETWEEN :fromDay AND :toDay " +
            "GROUP BY routineId",
    )
    fun routineTotals(
        fromDay: String,
        toDay: String,
        nowMillis: Long,
    ): Flow<List<RoutineTotalsRow>>
}
