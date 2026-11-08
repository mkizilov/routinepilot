package com.routinepilot.domain.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

enum class RecurrenceKind { DAILY, WEEKDAYS, EVERY_N_DAYS }

/** Which local dates a recurrence happens on. Dates before the recurrence's start date never match. */
sealed interface RecurrencePattern {
    val kind: RecurrenceKind

    fun matches(date: LocalDate, startDate: LocalDate): Boolean

    data object Daily : RecurrencePattern {
        override val kind = RecurrenceKind.DAILY

        override fun matches(date: LocalDate, startDate: LocalDate): Boolean = true
    }

    data class Weekdays(val days: Set<DayOfWeek>) : RecurrencePattern {
        init {
            require(days.isNotEmpty()) { "A weekday schedule needs at least one day" }
        }

        override val kind = RecurrenceKind.WEEKDAYS

        override fun matches(date: LocalDate, startDate: LocalDate): Boolean = date.dayOfWeek in days
    }

    /** Every [intervalDays] days counted from the start date, so the phase is fixed by that date. */
    data class EveryNDays(val intervalDays: Int) : RecurrencePattern {
        init {
            require(intervalDays in 1..MAX_INTERVAL_DAYS) { "Interval must be 1..$MAX_INTERVAL_DAYS days" }
        }

        override val kind = RecurrenceKind.EVERY_N_DAYS

        override fun matches(date: LocalDate, startDate: LocalDate): Boolean =
            ChronoUnit.DAYS.between(startDate, date) % intervalDays == 0L

        companion object {
            const val MAX_INTERVAL_DAYS = 365
        }
    }
}

/** A local time of day, on the dates the [pattern] selects, starting no earlier than [startDate]. */
data class Recurrence(
    val pattern: RecurrencePattern,
    val time: LocalTime,
    val startDate: LocalDate,
) {
    fun occursOn(date: LocalDate): Boolean = !date.isBefore(startDate) && pattern.matches(date, startDate)
}
