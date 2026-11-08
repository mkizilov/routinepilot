package com.routinepilot.domain.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Converts recurrence dates and local times into instants in a time zone.
 *
 * Daylight saving rules:
 * - A time inside a spring-forward gap is shifted forward by the length of the gap, so 02:30 on a day when clocks
 *   jump from 02:00 to 03:00 runs at 03:30 local time, which is 01:30 UTC in Europe/Berlin.
 * - A time inside a fall-back overlap (for example 01:30, which happens twice) runs once, at the earlier instant.
 * - Because the local time is kept, a 09:00 routine stays at 09:00 on the wall clock across the change.
 */
object ScheduleCalendar {
    fun instantAt(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(date, time, zone).toInstant()

    fun startOfDay(date: LocalDate, zone: ZoneId): Instant = date.atStartOfDay(zone).toInstant()

    fun dateOf(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

    /** Dates from [from] to [to], both inclusive, on which [recurrence] happens. */
    fun occurrenceDates(recurrence: Recurrence, from: LocalDate, to: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var date = if (from.isBefore(recurrence.startDate)) recurrence.startDate else from
        while (!date.isAfter(to)) {
            if (recurrence.occursOn(date)) dates += date
            date = date.plusDays(1)
        }
        return dates
    }
}
