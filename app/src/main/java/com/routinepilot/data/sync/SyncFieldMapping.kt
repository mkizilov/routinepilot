package com.routinepilot.data.sync

import com.routinepilot.data.mapping.maskOf
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.model.Task
import com.routinepilot.domain.schedule.RecurrencePattern
import com.routinepilot.domain.sync.FieldMap

/**
 * Turns the app's records into tracked field values in canonical text form. Every field named in
 * [com.routinepilot.domain.sync.SyncType] appears here. An empty value is null, so clearing a field is a change.
 */
object SyncFieldMapping {
    fun routine(routine: Routine): FieldMap = mapOf(
        "title" to routine.title,
        "description" to routine.description,
        "position" to routine.position.toString(),
    )

    fun step(step: RoutineStep): FieldMap = mapOf(
        "routineId" to step.routineId.toString(),
        "title" to step.title,
        "description" to step.description,
        "durationSeconds" to step.durationSeconds?.toString(),
        "position" to step.position.toString(),
    )

    fun task(task: Task): FieldMap = mapOf(
        "title" to task.title,
        "isCompleted" to task.isCompleted.toString(),
        "routineId" to task.routineId?.toString(),
        "position" to task.position.toString(),
    )

    fun schedule(schedule: Schedule): FieldMap {
        val pattern = schedule.recurrence.pattern
        val time = schedule.recurrence.time
        return mapOf(
            "routineId" to (schedule.target as? ScheduleTarget.RoutineTarget)?.routineId?.toString(),
            "taskId" to (schedule.target as? ScheduleTarget.TaskTarget)?.taskId?.toString(),
            "kind" to pattern.kind.name,
            "weekdaysMask" to (pattern as? RecurrencePattern.Weekdays)?.let { maskOf(it.days).toString() },
            "intervalDays" to (pattern as? RecurrencePattern.EveryNDays)?.intervalDays?.toString(),
            "timeOfDayMinutes" to (time.hour * MINUTES_PER_HOUR + time.minute).toString(),
            "startDate" to schedule.recurrence.startDate.toString(),
            "zoneId" to schedule.zone.id,
        )
    }

    private const val MINUTES_PER_HOUR = 60
}
