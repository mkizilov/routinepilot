package com.routinepilot.data.sync

import androidx.room.withTransaction
import com.routinepilot.data.mapping.daysOf
import com.routinepilot.data.mapping.toEntity
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.model.Task
import com.routinepilot.domain.schedule.Recurrence
import com.routinepilot.domain.schedule.RecurrenceKind
import com.routinepilot.domain.schedule.RecurrencePattern
import com.routinepilot.domain.sync.FieldMap
import com.routinepilot.domain.sync.LocalRecordWriter
import com.routinepilot.domain.sync.MergeResult
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.SyncType
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * Writes merged views into the app's tables, so server changes show up in the app.
 * Each record is written in one transaction.
 * Writes go to the DAOs directly, not through the repositories, so applying a change never queues a new one.
 * [createdAt] is
 * not
 * synced, so a record that arrives from the server gets the local time of arrival, and keeps it afterwards.
 */
class RoomLocalRecordWriter
    @Inject
    constructor(
        private val database: RoutinePilotDatabase,
        private val clock: Clock,
    ) : LocalRecordWriter {
        override suspend fun write(view: MergeResult) {
            val now = Instant.now(clock)
            database.withTransaction {
                when (view.key.type) {
                    SyncType.ROUTINE -> writeRoutine(view, now)
                    SyncType.ROUTINE_STEP -> writeStep(view, now)
                    SyncType.TASK -> writeTask(view, now)
                    SyncType.SCHEDULE -> writeSchedule(view, now)
                }
            }
        }

        private suspend fun writeRoutine(
            view: MergeResult,
            now: Instant,
        ) {
            val dao = database.routineDao()
            if (!view.exists) return dao.markDeleted(view.key.id, now)
            val createdAt = dao.findIncludingDeleted(view.key.id)?.createdAt ?: now
            val routine =
                Routine(
                    id = view.key.id,
                    title = view.fields.getValue("title").orEmpty(),
                    position = view.fields.intOf("position"),
                    createdAt = createdAt,
                    description = view.fields["description"],
                )
            dao.upsert(routine.toEntity(now))
        }

        private suspend fun writeStep(
            view: MergeResult,
            now: Instant,
        ) {
            val dao = database.routineStepDao()
            if (!view.exists) return dao.markDeleted(view.key.id, now)
            val createdAt = dao.findActive(view.key.id)?.createdAt ?: now
            val step =
                RoutineStep(
                    id = view.key.id,
                    routineId = view.fields.uuidOf("routineId"),
                    title = view.fields.getValue("title").orEmpty(),
                    position = view.fields.intOf("position"),
                    createdAt = createdAt,
                    description = view.fields["description"],
                    durationSeconds = view.fields["durationSeconds"]?.toInt(),
                )
            dao.upsert(listOf(step.toEntity(now)))
        }

        private suspend fun writeTask(
            view: MergeResult,
            now: Instant,
        ) {
            val dao = database.taskDao()
            if (!view.exists) return dao.markDeleted(view.key.id, now)
            val createdAt = dao.findActive(view.key.id)?.createdAt ?: now
            val task =
                Task(
                    id = view.key.id,
                    title = view.fields.getValue("title").orEmpty(),
                    isCompleted = view.fields.getValue("isCompleted").toBoolean(),
                    routineId = view.fields["routineId"]?.let(UUID::fromString),
                    position = view.fields.intOf("position"),
                    createdAt = createdAt,
                )
            dao.upsert(task.toEntity(now))
        }

        private suspend fun writeSchedule(
            view: MergeResult,
            now: Instant,
        ) {
            val dao = database.scheduleDao()
            if (!view.exists) {
                dao.markDeleted(view.key.id, now)
                return database.scheduledOccurrenceDao().deletePendingForSchedule(view.key.id)
            }
            val createdAt = dao.findById(view.key.id)?.createdAt ?: now
            dao.upsert(scheduleOf(view.key, view.fields, createdAt, now).toEntity())
        }

        private fun scheduleOf(
            key: ObjectKey,
            fields: FieldMap,
            createdAt: Instant,
            now: Instant,
        ): Schedule {
            val target =
                fields["routineId"]?.let { ScheduleTarget.RoutineTarget(UUID.fromString(it)) }
                    ?: ScheduleTarget.TaskTarget(fields.uuidOf("taskId"))
            val pattern =
                when (RecurrenceKind.valueOf(fields.getValue("kind").orEmpty())) {
                    RecurrenceKind.DAILY -> RecurrencePattern.Daily
                    RecurrenceKind.WEEKDAYS -> RecurrencePattern.Weekdays(daysOf(fields.intOf("weekdaysMask")))
                    RecurrenceKind.EVERY_N_DAYS -> RecurrencePattern.EveryNDays(fields.intOf("intervalDays"))
                }
            val minutes = fields.intOf("timeOfDayMinutes")
            return Schedule(
                id = key.id,
                target = target,
                recurrence =
                    Recurrence(
                        pattern = pattern,
                        time = LocalTime.of(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR),
                        startDate = LocalDate.parse(fields.getValue("startDate").orEmpty()),
                    ),
                zone = ZoneId.of(fields.getValue("zoneId").orEmpty()),
                createdAt = createdAt,
                modifiedAt = now,
            )
        }

        private fun FieldMap.intOf(name: String): Int = getValue(name).orEmpty().toInt()

        private fun FieldMap.uuidOf(name: String): UUID = UUID.fromString(getValue(name).orEmpty())

        private companion object {
            const val MINUTES_PER_HOUR = 60
        }
    }
