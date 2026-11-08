package com.routinepilot.domain.schedule

import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.model.ScheduledOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class ScheduleGeneratorTest {
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 3, 10)
    private val now = today.atTime(12, 0).atZone(utc).toInstant()
    private val scheduleId = UUID.fromString("00000000-0000-4000-8000-0000000000aa")

    @Test
    fun firstPlan_generatesTheWindowFromYesterdayToTwoWeeksAhead() {
        val modifiedAt = now.minusSeconds(60 * 60 * 24 * 60)
        val schedule = schedule(RecurrencePattern.Daily, start = today.minusDays(30), modifiedAt = modifiedAt)

        val plan = ScheduleGenerator.plan(schedule, emptyList(), now)

        val dates = plan.toInsert.map { it.localDate }
        assertEquals(today.minusDays(1), dates.first())
        assertEquals(today.plusDays(ScheduleGenerator.HORIZON_DAYS), dates.last())
        assertEquals(ScheduleGenerator.HORIZON_DAYS.toInt() + 2, dates.size)
    }

    @Test
    fun replanningTheSameState_createsNothing_soRestartsDoNotDuplicate() {
        val schedule = schedule(RecurrencePattern.Daily, start = today)
        val first = ScheduleGenerator.plan(schedule, emptyList(), now)
        val rows = first.toInsert.map { materialize(it, schedule) }

        val second = ScheduleGenerator.plan(schedule, rows, now)

        assertTrue(second.toInsert.isEmpty())
        assertTrue(second.toRemove.isEmpty())
    }

    @Test
    fun aDeletedSlot_isNotRegeneratedAfterRestart() {
        val schedule = schedule(RecurrencePattern.Daily, start = today)
        val rows = ScheduleGenerator.plan(schedule, emptyList(), now).toInsert.map { materialize(it, schedule) }
        val deleted = rows.map { if (it.localDate == today) it.copy(deletedAt = now) else it }

        val replan = ScheduleGenerator.plan(schedule, deleted, now)

        assertTrue(replan.toInsert.none { it.localDate == today })
    }

    @Test
    fun aCompletedSlot_isKeptAndNotRegenerated() {
        val schedule = schedule(RecurrencePattern.Daily, start = today)
        val rows = ScheduleGenerator.plan(schedule, emptyList(), now).toInsert.map { materialize(it, schedule) }
        val done = rows.map { if (it.localDate == today) it.copy(status = OccurrenceStatus.COMPLETED) else it }

        val replan = ScheduleGenerator.plan(schedule, done, now)

        assertTrue(replan.toInsert.none { it.localDate == today })
        assertTrue(replan.toRemove.isEmpty())
    }

    @Test
    fun changingWeekdays_removesPendingFutureSlotsTheRuleNoLongerProduces() {
        val weekdays = schedule(RecurrencePattern.Weekdays(setOf(DayOfWeek.MONDAY)), start = today)
        val rows = ScheduleGenerator.plan(weekdays, emptyList(), now).toInsert.map { materialize(it, weekdays) }

        val edited =
            weekdays.copy(
                recurrence = weekdays.recurrence.copy(pattern = RecurrencePattern.Weekdays(setOf(DayOfWeek.FRIDAY))),
                modifiedAt = now,
            )
        val plan = ScheduleGenerator.plan(edited, rows, now)

        val removed = rows.filter { it.id in plan.toRemove }
        assertTrue(removed.isNotEmpty())
        assertTrue(removed.all { it.localDate.dayOfWeek == DayOfWeek.MONDAY && it.localDate >= today })
        assertTrue(plan.toInsert.all { it.localDate.dayOfWeek == DayOfWeek.FRIDAY })
    }

    @Test
    fun changingTheTime_movesPendingSlots_byRemovingOldAndAddingNew() {
        val schedule = schedule(RecurrencePattern.Daily, start = today, time = LocalTime.of(8, 0))
        val rows = ScheduleGenerator.plan(schedule, emptyList(), now).toInsert.map { materialize(it, schedule) }

        val edited = schedule.copy(recurrence = schedule.recurrence.copy(time = LocalTime.of(9, 0)), modifiedAt = now)
        val plan = ScheduleGenerator.plan(edited, rows, now)

        assertEquals(rows.count { it.scheduledAt >= now }, plan.toRemove.size)
        assertEquals(
            plan.toInsert.size,
            plan.toInsert
                .map { it.originalScheduledAt }
                .toSet()
                .size,
        )
        assertTrue(plan.toInsert.all { it.originalScheduledAt.atZone(utc).hour == 9 })
    }

    @Test
    fun editingARule_neverCreatesItemsBeforeTheEditTime() {
        val editedAt = today.atTime(18, 0).atZone(utc).toInstant()
        val schedule = schedule(RecurrencePattern.Daily, start = today.minusDays(5), modifiedAt = editedAt)

        val plan = ScheduleGenerator.plan(schedule, emptyList(), now)

        assertTrue(plan.toInsert.all { it.originalScheduledAt >= editedAt })
    }

    @Test
    fun pastPendingItems_areKeptAsMissedHistory_notRemoved() {
        val schedule = schedule(RecurrencePattern.Daily, start = today.minusDays(3))
        val yesterday =
            materialize(
                PlannedOccurrence(
                    today
                        .minusDays(1)
                        .atTime(8, 0)
                        .atZone(utc)
                        .toInstant(),
                    today.minusDays(1),
                ),
                schedule,
            )

        val plan = ScheduleGenerator.plan(schedule, listOf(yesterday), now)

        assertTrue(yesterday.id !in plan.toRemove)
    }

    @Test
    fun rescheduledItem_survivesWhileItsOriginalSlotIsStillProduced() {
        val schedule = schedule(RecurrencePattern.Daily, start = today)
        val rows = ScheduleGenerator.plan(schedule, emptyList(), now).toInsert.map { materialize(it, schedule) }
        val moved =
            rows.map {
                if (it.localDate == today.plusDays(2)) {
                    it.copy(scheduledAt = it.scheduledAt.plusSeconds(3600), status = OccurrenceStatus.RESCHEDULED)
                } else {
                    it
                }
            }

        val plan = ScheduleGenerator.plan(schedule, moved, now)

        assertTrue(plan.toRemove.none { id -> moved.any { it.id == id && it.status == OccurrenceStatus.RESCHEDULED } })
        assertTrue(plan.toInsert.none { it.localDate == today.plusDays(2) })
    }

    @Test
    fun zoneChange_reanchorsPendingSlotsWithoutDuplicatingThem() {
        val paris = ZoneId.of("Europe/Paris")
        val schedule = schedule(RecurrencePattern.Daily, start = today, time = LocalTime.of(7, 0))
        val rows = ScheduleGenerator.plan(schedule, emptyList(), now).toInsert.map { materialize(it, schedule) }

        val movedZone = schedule.copy(zone = paris)
        val plan = ScheduleGenerator.plan(movedZone, rows, now)

        // 07:00 Paris is 06:00 UTC, so the old 07:00 UTC pending slots are no longer produced and are removed.
        val oldPending = rows.filter { it.originalScheduledAt >= now }.map { it.id }.toSet()
        assertEquals(oldPending, plan.toRemove.toSet())
        val existingInstants = rows.map { it.originalScheduledAt }.toSet()
        assertTrue(plan.toInsert.none { it.originalScheduledAt in existingInstants })
        assertTrue(plan.toInsert.all { it.originalScheduledAt.atZone(paris).toLocalTime() == LocalTime.of(7, 0) })
        assertEquals(
            plan.toInsert.size,
            plan.toInsert
                .map { it.originalScheduledAt }
                .toSet()
                .size,
        )
    }

    private fun schedule(
        pattern: RecurrencePattern,
        start: LocalDate,
        time: LocalTime = LocalTime.of(7, 0),
        modifiedAt: Instant = now.minusSeconds(60 * 60 * 24 * 60),
    ) = Schedule(
        id = scheduleId,
        target = ScheduleTarget.RoutineTarget(UUID.fromString("00000000-0000-4000-8000-0000000000bb")),
        recurrence = Recurrence(pattern, time, start),
        zone = utc,
        createdAt = modifiedAt,
        modifiedAt = modifiedAt,
    )

    private fun materialize(
        planned: PlannedOccurrence,
        schedule: Schedule,
    ) = ScheduledOccurrence(
        id = UUID.randomUUID(),
        scheduleId = schedule.id,
        routineId = (schedule.target as? ScheduleTarget.RoutineTarget)?.routineId,
        taskId = null,
        originalScheduledAt = planned.originalScheduledAt,
        scheduledAt = planned.originalScheduledAt,
        localDate = planned.localDate,
        status = OccurrenceStatus.PLANNED,
        createdAt = now,
    )
}
