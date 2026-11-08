package com.routinepilot.domain.validation

import com.routinepilot.domain.schedule.RecurrencePattern
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

enum class ScheduleValidationError {
    MISSING_ROUTINE,
    BLANK_TASK_TITLE,
    NO_WEEKDAYS,
    INTERVAL_OUT_OF_RANGE,
    INVALID_TIME,
    INVALID_START_DATE,
}

/** Parsing for schedule form fields. Each parser returns null for input it cannot accept. */
object ScheduleValidator {
    fun parseTime(hour: String, minute: String): LocalTime? {
        val h = hour.trim().toIntOrNull() ?: return null
        val m = minute.trim().toIntOrNull() ?: return null
        return if (h in 0..MAX_HOUR && m in 0..MAX_MINUTE) LocalTime.of(h, m) else null
    }

    fun parseInterval(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 1..RecurrencePattern.EveryNDays.MAX_INTERVAL_DAYS }

    /** Accepts ISO dates (yyyy-mm-dd) only, so the same text always means the same date in every locale. */
    fun parseStartDate(text: String): LocalDate? = try {
        LocalDate.parse(text.trim())
    } catch (malformed: DateTimeParseException) {
        null
    }

    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59
}
