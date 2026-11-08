package com.routinepilot.data.mapping

import com.routinepilot.domain.calendar.CalendarLink
import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.model.ScheduledOccurrence
import com.routinepilot.domain.model.StepCompletionEvent
import com.routinepilot.domain.model.Task
import com.routinepilot.domain.schedule.Recurrence
import com.routinepilot.domain.schedule.RecurrenceKind
import com.routinepilot.domain.schedule.RecurrencePattern
import com.routinepilot.infrastructure.database.CalendarLinkEntity
import com.routinepilot.infrastructure.database.ExecutionSessionEntity
import com.routinepilot.infrastructure.database.RoutineEntity
import com.routinepilot.infrastructure.database.RoutineStepEntity
import com.routinepilot.infrastructure.database.ScheduleEntity
import com.routinepilot.infrastructure.database.ScheduledOccurrenceEntity
import com.routinepilot.infrastructure.database.StepCompletionEventEntity
import com.routinepilot.infrastructure.database.TaskEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

internal fun RoutineEntity.toDomain() = Routine(id, title, position, createdAt, description)

internal fun Routine.toEntity(modifiedAt: Instant) =
    RoutineEntity(
        id = id,
        title = title,
        position = position,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        description = description,
    )

internal fun RoutineStepEntity.toDomain() =
    RoutineStep(id, routineId, title, position, createdAt, description, durationSeconds)

internal fun RoutineStep.toEntity(modifiedAt: Instant) =
    RoutineStepEntity(
        id = id,
        routineId = routineId,
        title = title,
        position = position,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        description = description,
        durationSeconds = durationSeconds,
    )

internal fun TaskEntity.toDomain() = Task(id, title, isCompleted, routineId, position, createdAt)

internal fun Task.toEntity(modifiedAt: Instant) =
    TaskEntity(
        id = id,
        title = title,
        isCompleted = isCompleted,
        routineId = routineId,
        position = position,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
    )

internal fun ScheduleEntity.toDomain(): Schedule {
    val target = if (routineId != null) {
        ScheduleTarget.RoutineTarget(routineId)
    } else {
        ScheduleTarget.TaskTarget(checkNotNull(taskId) { "Schedule $id has no target" })
    }
    val pattern = when (kind) {
        RecurrenceKind.DAILY -> RecurrencePattern.Daily
        RecurrenceKind.WEEKDAYS -> RecurrencePattern.Weekdays(daysOf(checkNotNull(weekdaysMask)))
        RecurrenceKind.EVERY_N_DAYS -> RecurrencePattern.EveryNDays(checkNotNull(intervalDays))
    }
    return Schedule(
        id = id,
        target = target,
        recurrence = Recurrence(pattern, timeOfDay(), startDate),
        zone = zoneId,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
    )
}

private fun ScheduleEntity.timeOfDay(): LocalTime =
    LocalTime.of(timeOfDayMinutes / MINUTES_PER_HOUR, timeOfDayMinutes % MINUTES_PER_HOUR)

internal fun Schedule.toEntity(): ScheduleEntity {
    val (routineId, taskId) = when (val target = target) {
        is ScheduleTarget.RoutineTarget -> target.routineId to null
        is ScheduleTarget.TaskTarget -> null to target.taskId
    }
    val pattern = recurrence.pattern
    return ScheduleEntity(
        id = id,
        routineId = routineId,
        taskId = taskId,
        kind = pattern.kind,
        weekdaysMask = (pattern as? RecurrencePattern.Weekdays)?.let { maskOf(it.days) },
        intervalDays = (pattern as? RecurrencePattern.EveryNDays)?.intervalDays,
        timeOfDayMinutes = recurrence.time.hour * MINUTES_PER_HOUR + recurrence.time.minute,
        startDate = recurrence.startDate,
        zoneId = zone,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
    )
}

/** Monday is bit 0, so the mask reads in the same order as the week. */
internal fun maskOf(days: Set<DayOfWeek>): Int = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

internal fun daysOf(mask: Int): Set<DayOfWeek> =
    DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()

internal fun ScheduledOccurrenceEntity.toDomain() = ScheduledOccurrence(
    id = id,
    scheduleId = scheduleId,
    routineId = routineId,
    taskId = taskId,
    originalScheduledAt = originalScheduledAt,
    scheduledAt = scheduledAt,
    localDate = localDate,
    status = status,
    createdAt = createdAt,
    deletedAt = deletedAt,
)

internal fun ScheduledOccurrence.toEntity(modifiedAt: Instant) =
    ScheduledOccurrenceEntity(
        id = id,
        scheduleId = scheduleId,
        routineId = routineId,
        taskId = taskId,
        originalScheduledAt = originalScheduledAt,
        scheduledAt = scheduledAt,
        localDate = localDate,
        status = status,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        deletedAt = deletedAt,
    )

internal fun ExecutionSessionEntity.toDomain() = ExecutionSession(
    id = id,
    routineId = routineId,
    occurrenceId = occurrenceId,
    startedAt = startedAt,
    endedAt = endedAt,
    createdAt = createdAt,
    outcome = outcome,
    focusedMillis = focusedMillis,
    localDate = localDate,
)

internal fun ExecutionSession.toEntity(modifiedAt: Instant) =
    ExecutionSessionEntity(
        id = id,
        routineId = routineId,
        occurrenceId = occurrenceId,
        startedAt = startedAt,
        endedAt = endedAt,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        outcome = outcome,
        focusedMillis = focusedMillis,
        localDate = localDate,
    )

internal fun StepCompletionEventEntity.toDomain() =
    StepCompletionEvent(id, sessionId, stepId, completedAt, createdAt, localDate)

internal fun StepCompletionEvent.toEntity(modifiedAt: Instant) =
    StepCompletionEventEntity(
        id = id,
        sessionId = sessionId,
        stepId = stepId,
        completedAt = completedAt,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        localDate = localDate,
    )

internal fun CalendarLinkEntity.toDomain() = CalendarLink(
    scheduleId = scheduleId,
    calendarId = calendarId,
    eventId = eventId,
    uid = uid,
    allDay = allDay,
    rrule = rrule,
    startMillis = startMillis,
    ruleKey = ruleKey,
    contentHash = contentHash,
    status = status,
    updatedAt = updatedAt,
)

internal fun CalendarLink.toEntity() = CalendarLinkEntity(
    scheduleId = scheduleId,
    calendarId = calendarId,
    eventId = eventId,
    uid = uid,
    allDay = allDay,
    rrule = rrule,
    startMillis = startMillis,
    ruleKey = ruleKey,
    contentHash = contentHash,
    status = status,
    updatedAt = updatedAt,
)

private const val MINUTES_PER_HOUR = 60
