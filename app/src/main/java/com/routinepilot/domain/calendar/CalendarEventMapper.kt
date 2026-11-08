package com.routinepilot.domain.calendar

import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.schedule.RecurrencePattern
import com.routinepilot.domain.schedule.ScheduleCalendar
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Turns a schedule into a calendar event.
 *
 * Each schedule becomes one recurring event, so an edit changes a single event instead of many. Times follow the
 * schedule's own zone: a timed event uses that zone and keeps its wall-clock time across daylight-saving changes.
 * All-day events use UTC midnight, as the Calendar Provider requires.
 *
 * DTSTART is the first occurrence on or after the later of the start date and the day the rule was last saved. While
 * the rule keeps the same [ruleKeyFor], the series keeps the start it already has. A re-save of an unchanged schedule
 * on a later day therefore never moves past events out of the calendar.
 */
object CalendarEventMapper {
    const val DEFAULT_ROUTINE_SECONDS = 30 * 60
    const val TASK_SECONDS = 15 * 60
    const val MIN_TIMED_SECONDS = 5 * 60
    const val MAX_TIMED_SECONDS = 12 * 60 * 60

    private const val SEARCH_DAYS = 400L
    private const val UID_DOMAIN = "routinepilot.local"
    private const val SECONDS_PER_MINUTE = 60
    private val UTC_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun uidFor(scheduleId: UUID): String = "routinepilot-schedule-$scheduleId@$UID_DOMAIN"

    /**
     * Null only if the schedule has no occurrence within the search window, which valid rules do not produce.
     * [previous] is the link the schedule already has, if any, so its series start can be kept.
     */
    fun draftFor(
        source: CalendarSource,
        allDay: Boolean,
        previous: CalendarLink?,
    ): CalendarEventDraft? {
        val schedule = source.schedule
        val firstDate =
            previous?.takeIf { it.ruleKey == ruleKeyFor(schedule) }?.let { startDateOf(it, schedule.zone) }
                ?: firstOccurrence(schedule)
                ?: return null
        return CalendarEventDraft(
            uid = uidFor(schedule.id),
            title = source.title,
            description = descriptionFor(source),
            timeZoneId = if (allDay) ALL_DAY_ZONE else schedule.zone.id,
            allDay = allDay,
            startMillis = startMillisOn(firstDate, schedule, allDay),
            durationText = if (allDay) "P1D" else "PT${timedSeconds(source)}S",
            rrule = ruleFor(schedule.recurrence.pattern),
        )
    }

    fun ruleFor(pattern: RecurrencePattern): String =
        when (pattern) {
            RecurrencePattern.Daily -> "FREQ=DAILY"
            is RecurrencePattern.Weekdays ->
                "FREQ=WEEKLY;BYDAY=" + pattern.days.sortedBy { it.value }.joinToString(",") { byDay(it) }
            is RecurrencePattern.EveryNDays -> "FREQ=DAILY;INTERVAL=${pattern.intervalDays}"
        }

    /** Everything that defines the series start. Changing any part moves the start to the day the rule was saved. */
    fun ruleKeyFor(schedule: Schedule): String {
        val recurrence = schedule.recurrence
        return listOf(
            ruleFor(recurrence.pattern),
            recurrence.time.toString(),
            recurrence.startDate.toString(),
            schedule.zone.id,
        ).joinToString(SEPARATOR)
    }

    /**
     * The same rule, ended at [endAt]. A timed series stops at that instant. An all-day series stops before the day of
     * [endAt], so the event does not show a day that has already been stopped.
     */
    fun endedRule(
        rrule: String,
        endAt: Instant,
        allDay: Boolean,
    ): String = "$rrule;UNTIL=${untilValue(endAt, allDay)}"

    /** True when ending at [endAt] would leave no occurrence, so the event has nothing left to show. */
    fun endsBeforeFirstOccurrence(
        startMillis: Long,
        endAt: Instant,
        allDay: Boolean,
    ): Boolean {
        val start = Instant.ofEpochMilli(startMillis)
        return if (allDay) {
            untilDate(endAt).isBefore(start.atZone(ZoneOffset.UTC).toLocalDate())
        } else {
            !endAt.isAfter(start)
        }
    }

    /** A hash over every field the calendar stores. Equal hashes mean an update would change nothing. */
    fun contentHash(draft: CalendarEventDraft): String {
        val canonical =
            listOf(
                draft.uid,
                draft.title,
                draft.description,
                draft.timeZoneId,
                draft.allDay.toString(),
                draft.startMillis.toString(),
                draft.durationText,
                draft.rrule,
            ).joinToString(SEPARATOR)
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun firstOccurrence(schedule: Schedule): LocalDate? {
        val recurrence = schedule.recurrence
        val anchor = maxOf(recurrence.startDate, ScheduleCalendar.dateOf(schedule.modifiedAt, schedule.zone))
        return ScheduleCalendar.occurrenceDates(recurrence, anchor, anchor.plusDays(SEARCH_DAYS)).firstOrNull()
    }

    /** The local date a stored series starts on, read back from its start in the form it was stored in. */
    private fun startDateOf(
        link: CalendarLink,
        zone: ZoneId,
    ): LocalDate =
        Instant.ofEpochMilli(link.startMillis).atZone(if (link.allDay) ZoneOffset.UTC else zone).toLocalDate()

    private fun startMillisOn(
        date: LocalDate,
        schedule: Schedule,
        allDay: Boolean,
    ): Long =
        if (allDay) {
            date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            ScheduleCalendar.instantAt(date, schedule.recurrence.time, schedule.zone).toEpochMilli()
        }

    private fun untilValue(
        endAt: Instant,
        allDay: Boolean,
    ): String =
        if (allDay) {
            untilDate(endAt).format(DateTimeFormatter.BASIC_ISO_DATE)
        } else {
            UTC_STAMP.format(endAt)
        }

    private fun untilDate(endAt: Instant): LocalDate = endAt.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)

    private fun timedSeconds(source: CalendarSource): Int {
        if (source.isTask) return TASK_SECONDS
        val total = source.steps.sumOf { it.durationSeconds ?: 0 }
        val seconds = if (total <= 0) DEFAULT_ROUTINE_SECONDS else total
        return seconds.coerceIn(MIN_TIMED_SECONDS, MAX_TIMED_SECONDS)
    }

    private fun descriptionFor(source: CalendarSource): String {
        if (source.isTask) return "Task from RoutinePilot."
        val steps =
            source.steps.mapIndexed { index, step ->
                val minutes = step.durationSeconds?.let { " (${ceilMinutes(it)} min)" }.orEmpty()
                "${index + 1}. ${step.title}$minutes"
            }
        return (listOf("Routine from RoutinePilot.") + steps).joinToString("\n")
    }

    private fun ceilMinutes(seconds: Int): Int = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE

    private fun byDay(day: DayOfWeek): String =
        when (day) {
            DayOfWeek.MONDAY -> "MO"
            DayOfWeek.TUESDAY -> "TU"
            DayOfWeek.WEDNESDAY -> "WE"
            DayOfWeek.THURSDAY -> "TH"
            DayOfWeek.FRIDAY -> "FR"
            DayOfWeek.SATURDAY -> "SA"
            DayOfWeek.SUNDAY -> "SU"
        }

    private const val SEPARATOR = "\u001F"

    /** The Calendar Provider requires this exact name for all-day events. ZoneOffset.UTC would give "Z". */
    private const val ALL_DAY_ZONE = "UTC"
}
