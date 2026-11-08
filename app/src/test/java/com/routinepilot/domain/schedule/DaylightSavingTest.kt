package com.routinepilot.domain.schedule

import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Daylight-saving behaviour is checked against Europe/Berlin, which springs forward on 2026-03-29 (02:00 to 03:00)
 * and falls back on 2026-10-25 (03:00 to 02:00). Expected instants are written out explicitly so the test does not
 * depend on the same library code it checks.
 */
class DaylightSavingTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun springForward_aTimeInsideTheGapIsShiftedForwardByTheGapLength() {
        // 02:30 does not exist on 2026-03-29. It is shifted by the one-hour gap to 03:30 CEST, which is 01:30 UTC.
        val instant = ScheduleCalendar.instantAt(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), berlin)

        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), instant)
        assertEquals(LocalTime.of(3, 30), instant.atZone(berlin).toLocalTime())
    }

    @Test
    fun springForward_aTimeBeforeTheGapIsUnaffected() {
        val instant = ScheduleCalendar.instantAt(LocalDate.of(2026, 3, 29), LocalTime.of(1, 59), berlin)

        assertEquals(Instant.parse("2026-03-29T00:59:00Z"), instant)
    }

    @Test
    fun fallBack_aTimeInsideTheOverlapRunsOnceAtTheEarlierInstant() {
        // 02:30 happens twice on 2026-10-25. The earlier one is CEST: 00:30 UTC.
        val instant = ScheduleCalendar.instantAt(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), berlin)

        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), instant)
    }

    @Test
    fun dailyRoutineKeepsItsWallClockTimeAcrossTheSpringTransition() {
        val recurrence = Recurrence(RecurrencePattern.Daily, LocalTime.of(9, 0), LocalDate.of(2026, 3, 27))
        val wallClockTimes = ScheduleCalendar
            .occurrenceDates(recurrence, LocalDate.of(2026, 3, 27), LocalDate.of(2026, 3, 30))
            .map { ScheduleCalendar.instantAt(it, recurrence.time, berlin).atZone(berlin).toLocalTime() }

        assertEquals(List(4) { LocalTime.of(9, 0) }, wallClockTimes)
    }

    @Test
    fun dailyRoutineKeepsItsWallClockTimeAcrossTheAutumnTransition() {
        val recurrence = Recurrence(RecurrencePattern.Daily, LocalTime.of(9, 0), LocalDate.of(2026, 10, 24))
        val wallClockTimes = ScheduleCalendar
            .occurrenceDates(recurrence, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 26))
            .map { ScheduleCalendar.instantAt(it, recurrence.time, berlin).atZone(berlin).toLocalTime() }

        assertEquals(List(3) { LocalTime.of(9, 0) }, wallClockTimes)
    }

    @Test
    fun springForwardDay_planHasOneOccurrencePerDateAndDistinctInstants() {
        val schedule = berlinSchedule(RecurrencePattern.Daily, LocalTime.of(2, 30), LocalDate.of(2026, 3, 27))

        val plan = ScheduleGenerator.plan(schedule, emptyList(), Instant.parse("2026-03-27T10:00:00Z"))

        val instants = plan.toInsert.map { it.originalScheduledAt }
        assertEquals(instants.size, instants.toSet().size)
        assertEquals(plan.toInsert.map { it.localDate }.toSet().size, plan.toInsert.size)
    }

    /**
     * On the fall-back day 02:30 happens twice. Planning is per calendar date, and each date maps to one instant, so
     * the repeated hour yields one occurrence for that day, not two.
     */
    @Test
    fun fallBackDay_repeatedHourYieldsOneOccurrenceForThatDate() {
        val schedule = berlinSchedule(RecurrencePattern.Daily, LocalTime.of(2, 30), LocalDate.of(2026, 10, 24))

        val plan = ScheduleGenerator.plan(schedule, emptyList(), Instant.parse("2026-10-24T10:00:00Z"))

        val onTransitionDay = plan.toInsert.filter { it.localDate == LocalDate.of(2026, 10, 25) }
        assertEquals(1, onTransitionDay.size)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), onTransitionDay.single().originalScheduledAt)
    }

    /**
     * These expectations were computed from the IANA time-zone database (zoneinfo), not from this code. They cover
     * zones with other transition rules: New York, and Lord Howe Island, which changes by only 30 minutes.
     */
    @Test
    fun newYorkSpringForward_gapTimeIsShiftedForward() {
        val newYork = ZoneId.of("America/New_York")

        val instant = ScheduleCalendar.instantAt(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), newYork)

        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), instant)
    }

    @Test
    fun newYorkFallBack_overlapTimeRunsOnceAtTheEarlierInstant() {
        val newYork = ZoneId.of("America/New_York")

        val instant = ScheduleCalendar.instantAt(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30), newYork)

        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), instant)
    }

    @Test
    fun lordHoweSpringForward_halfHourGapIsShiftedByThirtyMinutes() {
        val lordHowe = ZoneId.of("Australia/Lord_Howe")

        val instant = ScheduleCalendar.instantAt(LocalDate.of(2026, 10, 4), LocalTime.of(2, 15), lordHowe)

        assertEquals(Instant.parse("2026-10-03T15:45:00Z"), instant)
    }

    private fun berlinSchedule(pattern: RecurrencePattern, time: LocalTime, start: LocalDate) = Schedule(
        id = UUID.fromString("00000000-0000-4000-8000-000000000001"),
        target = ScheduleTarget.RoutineTarget(UUID.fromString("00000000-0000-4000-8000-000000000002")),
        recurrence = Recurrence(pattern, time, start),
        zone = berlin,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        modifiedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
