package com.routinepilot.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class RecurrenceTest {
    private val monday = LocalDate.of(2026, 3, 2)

    @Test
    fun daily_occursEveryDayFromTheStartDate() {
        val recurrence = Recurrence(RecurrencePattern.Daily, LocalTime.of(7, 0), monday)

        val dates = ScheduleCalendar.occurrenceDates(recurrence, monday, monday.plusDays(6))

        assertEquals(7, dates.size)
    }

    @Test
    fun neverOccursBeforeTheStartDate() {
        val recurrence = Recurrence(RecurrencePattern.Daily, LocalTime.of(7, 0), monday)

        assertFalse(recurrence.occursOn(monday.minusDays(1)))
        assertEquals(
            listOf(monday, monday.plusDays(1)),
            ScheduleCalendar.occurrenceDates(recurrence, monday.minusDays(10), monday.plusDays(1)),
        )
    }

    @Test
    fun weekdays_occursOnlyOnSelectedDays() {
        val pattern = RecurrencePattern.Weekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        val recurrence = Recurrence(pattern, LocalTime.of(9, 30), monday)

        val dates = ScheduleCalendar.occurrenceDates(recurrence, monday, monday.plusDays(13))

        assertEquals(
            listOf(
                monday,
                monday.plusDays(2),
                monday.plusDays(4),
                monday.plusDays(7),
                monday.plusDays(9),
                monday.plusDays(11),
            ),
            dates,
        )
    }

    @Test
    fun weekdays_requiresAtLeastOneDay() {
        assertThrows(IllegalArgumentException::class.java) { RecurrencePattern.Weekdays(emptySet()) }
    }

    @Test
    fun everyNDays_isAnchoredToTheStartDate() {
        val recurrence = Recurrence(RecurrencePattern.EveryNDays(3), LocalTime.of(8, 0), monday)

        val dates = ScheduleCalendar.occurrenceDates(recurrence, monday, monday.plusDays(10))

        assertEquals(listOf(monday, monday.plusDays(3), monday.plusDays(6), monday.plusDays(9)), dates)
    }

    @Test
    fun everyNDays_keepsItsPhaseWhenTheWindowStartsLater() {
        val recurrence = Recurrence(RecurrencePattern.EveryNDays(3), LocalTime.of(8, 0), monday)

        val dates = ScheduleCalendar.occurrenceDates(recurrence, monday.plusDays(4), monday.plusDays(7))

        assertEquals(listOf(monday.plusDays(6)), dates)
    }

    @Test
    fun everyNDays_rejectsIntervalsOutsideTheSupportedRange() {
        assertThrows(IllegalArgumentException::class.java) { RecurrencePattern.EveryNDays(0) }
        assertThrows(IllegalArgumentException::class.java) {
            RecurrencePattern.EveryNDays(RecurrencePattern.EveryNDays.MAX_INTERVAL_DAYS + 1)
        }
    }

    @Test
    fun everyNDays_reportsItsKind() {
        assertEquals(RecurrenceKind.EVERY_N_DAYS, RecurrencePattern.EveryNDays(2).kind)
        assertTrue(RecurrencePattern.Daily.matches(monday, monday))
    }
}
