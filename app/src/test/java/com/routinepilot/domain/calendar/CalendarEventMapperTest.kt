package com.routinepilot.domain.calendar

import com.routinepilot.domain.schedule.RecurrencePattern
import com.routinepilot.testing.calendarSchedule
import com.routinepilot.testing.calendarScheduleId
import com.routinepilot.testing.calendarSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class CalendarEventMapperTest {
    private val now = Instant.parse("2026-03-10T12:00:00Z")

    @Test
    fun uid_isStableAcrossResaves() {
        val first = CalendarEventMapper.draftFor(calendarSource(), allDay = false, previous = null)!!
        val resaved = calendarSource(schedule = calendarSchedule(modifiedAt = now))
        val second = CalendarEventMapper.draftFor(resaved, allDay = false, previous = null)!!

        assertEquals(CalendarEventMapper.uidFor(calendarScheduleId), first.uid)
        assertEquals(first.uid, second.uid)
    }

    @Test
    fun timedStart_usesTheScheduleZoneWallClock() {
        val draft = CalendarEventMapper.draftFor(calendarSource(), allDay = false, previous = null)!!

        assertEquals(Instant.parse("2026-03-10T08:00:00Z").toEpochMilli(), draft.startMillis)
        assertEquals("Europe/Berlin", draft.timeZoneId)
        assertFalse(draft.allDay)
    }

    @Test
    fun timedStart_afterDaylightSavingKeepsTheNineOClockWallClock() {
        val source = calendarSource(
            schedule = calendarSchedule(
                startDate = LocalDate.of(2026, 4, 1),
                modifiedAt = Instant.parse("2026-04-01T06:00:00Z"),
            ),
        )

        val draft = CalendarEventMapper.draftFor(source, allDay = false, previous = null)!!

        assertEquals(Instant.parse("2026-04-01T07:00:00Z").toEpochMilli(), draft.startMillis)
    }

    @Test
    fun allDay_usesUtcMidnightUtcZoneAndOneDay() {
        val draft = CalendarEventMapper.draftFor(calendarSource(), allDay = true, previous = null)!!

        assertEquals(Instant.parse("2026-03-10T00:00:00Z").toEpochMilli(), draft.startMillis)
        assertEquals("UTC", draft.timeZoneId)
        assertEquals("P1D", draft.durationText)
        assertTrue(draft.allDay)
    }

    @Test
    fun timedDuration_isTheSumOfStepDurations() {
        val source = calendarSource(steps = listOf(CalendarStep("a", 600), CalendarStep("b", 1200)))

        assertEquals("PT1800S", CalendarEventMapper.draftFor(source, false, null)!!.durationText)
    }

    @Test
    fun timedDuration_defaultsToThirtyMinutesWhenNoStepHasADuration() {
        val source = calendarSource(steps = listOf(CalendarStep("a", null)))

        assertEquals("PT1800S", CalendarEventMapper.draftFor(source, false, null)!!.durationText)
    }

    @Test
    fun timedDuration_isClampedToFiveMinutesAndTwelveHours() {
        val short = calendarSource(steps = listOf(CalendarStep("a", 60)))
        val long = calendarSource(steps = listOf(CalendarStep("a", 14 * 3600)))

        assertEquals("PT300S", CalendarEventMapper.draftFor(short, false, null)!!.durationText)
        assertEquals("PT43200S", CalendarEventMapper.draftFor(long, false, null)!!.durationText)
    }

    @Test
    fun task_usesFifteenMinutesAndASimpleDescription() {
        val draft = CalendarEventMapper.draftFor(calendarSource(steps = emptyList(), isTask = true), false, null)!!

        assertEquals("PT900S", draft.durationText)
        assertEquals("Task from RoutinePilot.", draft.description)
    }

    @Test
    fun routineDescription_listsStepsWithRoundedUpMinutes() {
        val draft = CalendarEventMapper.draftFor(calendarSource(), false, null)!!

        assertEquals(
            "Routine from RoutinePilot.\n1. Brush teeth (2 min)\n2. Water plants",
            draft.description,
        )
    }

    @Test
    fun ruleFor_mapsEachPattern() {
        assertEquals("FREQ=DAILY", CalendarEventMapper.ruleFor(RecurrencePattern.Daily))
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO,WE,FR",
            CalendarEventMapper.ruleFor(
                RecurrencePattern.Weekdays(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)),
            ),
        )
        assertEquals("FREQ=DAILY;INTERVAL=3", CalendarEventMapper.ruleFor(RecurrencePattern.EveryNDays(3)))
    }

    @Test
    fun endedRule_timedSeries_stopsAtTheInstant() {
        val rule = CalendarEventMapper.endedRule("FREQ=DAILY", Instant.parse("2026-03-12T10:15:00Z"), allDay = false)

        assertEquals("FREQ=DAILY;UNTIL=20260312T101500Z", rule)
    }

    @Test
    fun endedRule_allDaySeries_stopsBeforeTheStopDay() {
        val rule = CalendarEventMapper.endedRule("FREQ=DAILY", Instant.parse("2026-03-12T10:00:00Z"), allDay = true)

        assertEquals("FREQ=DAILY;UNTIL=20260311", rule)
    }

    @Test
    fun endsBeforeFirstOccurrence_timedAndAllDay() {
        val timedStart = Instant.parse("2026-03-10T08:00:00Z").toEpochMilli()
        val allDayStart = Instant.parse("2026-03-10T00:00:00Z").toEpochMilli()

        fun endsBefore(start: Long, endAt: String, allDay: Boolean) =
            CalendarEventMapper.endsBeforeFirstOccurrence(start, Instant.parse(endAt), allDay)

        assertTrue(endsBefore(timedStart, "2026-03-10T07:59:59Z", allDay = false))
        assertTrue(endsBefore(timedStart, "2026-03-10T08:00:00Z", allDay = false))
        assertFalse(endsBefore(timedStart, "2026-03-10T08:00:01Z", allDay = false))
        assertTrue(endsBefore(allDayStart, "2026-03-10T23:00:00Z", allDay = true))
        assertFalse(endsBefore(allDayStart, "2026-03-11T00:30:00Z", allDay = true))
    }

    @Test
    fun contentHash_isStableForEqualDrafts_andChangesWithAnyStoredField() {
        val draft = CalendarEventMapper.draftFor(calendarSource(), false, null)!!
        val retitled = CalendarEventMapper.draftFor(calendarSource(title = "Evening"), false, null)!!
        val allDay = CalendarEventMapper.draftFor(calendarSource(), true, null)!!

        assertEquals(CalendarEventMapper.contentHash(draft), CalendarEventMapper.contentHash(draft.copy()))
        assertNotEquals(CalendarEventMapper.contentHash(draft), CalendarEventMapper.contentHash(retitled))
        assertNotEquals(CalendarEventMapper.contentHash(draft), CalendarEventMapper.contentHash(allDay))
    }

    @Test
    fun resaveOnALaterDay_keepsTheSeriesStart_soPastEventsStay() {
        val first = CalendarEventMapper.draftFor(calendarSource(), false, null)!!
        val link = linkWith(first)
        val resaved = calendarSource(schedule = calendarSchedule(modifiedAt = Instant.parse("2026-03-20T08:00:00Z")))

        val kept = CalendarEventMapper.draftFor(resaved, false, link)!!
        val reanchored = CalendarEventMapper.draftFor(resaved, false, null)!!

        assertEquals(first.startMillis, kept.startMillis)
        assertEquals(Instant.parse("2026-03-20T08:00:00Z").toEpochMilli(), reanchored.startMillis)
    }

    @Test
    fun ruleChange_movesTheSeriesStartToTheSaveDay() {
        val first = CalendarEventMapper.draftFor(calendarSource(), false, null)!!
        val link = linkWith(first)
        val changed = calendarSource(
            schedule = calendarSchedule(
                time = LocalTime.of(10, 0),
                modifiedAt = Instant.parse("2026-03-20T08:00:00Z"),
            ),
        )

        val draft = CalendarEventMapper.draftFor(changed, false, link)!!

        assertEquals(Instant.parse("2026-03-20T09:00:00Z").toEpochMilli(), draft.startMillis)
    }

    @Test
    fun allDayToggle_keepsTheSeriesDate() {
        val timed = CalendarEventMapper.draftFor(calendarSource(), false, null)!!
        val link = linkWith(timed)

        val allDay = CalendarEventMapper.draftFor(calendarSource(), true, link)!!
        val back = CalendarEventMapper.draftFor(calendarSource(), false, linkWith(allDay))!!

        assertEquals(Instant.parse("2026-03-10T00:00:00Z").toEpochMilli(), allDay.startMillis)
        assertEquals(timed.startMillis, back.startMillis)
    }

    private fun linkWith(draft: CalendarEventDraft) = CalendarLink(
        scheduleId = calendarScheduleId,
        calendarId = 1L,
        eventId = 100L,
        uid = draft.uid,
        allDay = draft.allDay,
        rrule = draft.rrule,
        startMillis = draft.startMillis,
        ruleKey = CalendarEventMapper.ruleKeyFor(calendarSchedule()),
        contentHash = CalendarEventMapper.contentHash(draft),
        status = CalendarLinkStatus.LINKED,
        updatedAt = now,
    )
}
