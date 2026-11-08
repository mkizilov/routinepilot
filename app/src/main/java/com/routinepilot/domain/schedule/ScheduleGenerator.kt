package com.routinepilot.domain.schedule

import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduledOccurrence
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class PlannedOccurrence(
    val originalScheduledAt: Instant,
    val localDate: LocalDate,
)

/** What must change so that a schedule's occurrences match its rule. Nothing outside this plan changes. */
data class GenerationPlan(
    val toInsert: List<PlannedOccurrence>,
    val toRemove: List<UUID>,
)

/**
 * Plans occurrences for one schedule. Calling it again with the rows it already produced returns an empty plan,
 * so repeating generation after a restart cannot create duplicates.
 *
 * Rules:
 * - Only instants at or after [Schedule.modifiedAt] are generated, so editing a rule never adds items in the past.
 * - The window runs from yesterday to [ScheduleGenerator.HORIZON_DAYS] days ahead in the schedule's zone.
 * - An instant is identified by its original time. A row that already exists for it, whatever its status or
 *   whether it was deleted, blocks regeneration. A deleted row stays deleted.
 * - Pending rows at or after [now] that the rule no longer produces are removed. Completed, skipped, and past rows
 *   are kept as history. A rescheduled row survives only while its original time is still produced.
 */
object ScheduleGenerator {
    const val PAST_DAYS = 1L
    const val HORIZON_DAYS = 14L

    fun plan(
        schedule: Schedule,
        existing: List<ScheduledOccurrence>,
        now: Instant,
    ): GenerationPlan {
        val zone = schedule.zone
        val today = ScheduleCalendar.dateOf(now, zone)
        val windowFrom = today.minusDays(PAST_DAYS)
        val windowTo = today.plusDays(HORIZON_DAYS)
        val windowEnd = ScheduleCalendar.startOfDay(windowTo.plusDays(1), zone)
        val floor = maxOf(schedule.modifiedAt, ScheduleCalendar.startOfDay(windowFrom, zone))

        val latestExisting = existing.maxOfOrNull { ScheduleCalendar.dateOf(it.originalScheduledAt, zone) }
        val rangeTo = maxOf(windowTo, latestExisting ?: windowTo)
        val produced =
            ScheduleCalendar.occurrenceDates(schedule.recurrence, windowFrom, rangeTo).map { date ->
                PlannedOccurrence(ScheduleCalendar.instantAt(date, schedule.recurrence.time, zone), date)
            }
        val producedInstants = produced.map { it.originalScheduledAt }.toSet()
        val knownInstants = existing.map { it.originalScheduledAt }.toSet()

        val toInsert =
            produced.filter {
                it.originalScheduledAt >= floor &&
                    it.originalScheduledAt < windowEnd &&
                    it.originalScheduledAt !in knownInstants
            }
        val toRemove =
            existing
                .filter {
                    it.deletedAt == null &&
                        it.status.isPending &&
                        it.originalScheduledAt >= now &&
                        it.originalScheduledAt !in producedInstants
                }.map { it.id }

        return GenerationPlan(toInsert, toRemove)
    }
}
