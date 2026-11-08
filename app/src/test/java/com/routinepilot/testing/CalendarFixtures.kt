package com.routinepilot.testing

import com.routinepilot.domain.calendar.CalendarSource
import com.routinepilot.domain.calendar.CalendarStep
import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.schedule.Recurrence
import com.routinepilot.domain.schedule.RecurrencePattern
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

val calendarScheduleId: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000aa")
private val calendarRoutineId = UUID.fromString("00000000-0000-4000-8000-0000000000bb")
val berlinZone: ZoneId = ZoneId.of("Europe/Berlin")

/** A daily 09:00 Berlin schedule, saved on 2026-03-10 at 09:00 local time. */
fun calendarSchedule(
    pattern: RecurrencePattern = RecurrencePattern.Daily,
    time: LocalTime = LocalTime.of(9, 0),
    startDate: LocalDate = LocalDate.of(2026, 3, 10),
    modifiedAt: Instant = Instant.parse("2026-03-10T08:00:00Z"),
    zone: ZoneId = berlinZone,
): Schedule = Schedule(
    id = calendarScheduleId,
    target = ScheduleTarget.RoutineTarget(calendarRoutineId),
    recurrence = Recurrence(pattern, time, startDate),
    zone = zone,
    createdAt = modifiedAt,
    modifiedAt = modifiedAt,
)

fun calendarSource(
    schedule: Schedule = calendarSchedule(),
    title: String = "Morning",
    steps: List<CalendarStep> = listOf(CalendarStep("Brush teeth", 90), CalendarStep("Water plants", null)),
    isTask: Boolean = false,
): CalendarSource = CalendarSource(schedule, title, isTask, steps)
