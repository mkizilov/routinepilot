package com.routinepilot.data.repository

import androidx.room.withTransaction
import com.routinepilot.data.mapping.toDomain
import com.routinepilot.data.mapping.toEntity
import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.model.ScheduledOccurrence
import com.routinepilot.domain.repository.ScheduleRepository
import com.routinepilot.domain.schedule.AgendaItem
import com.routinepilot.domain.schedule.AgendaKind
import com.routinepilot.domain.schedule.OccurrenceTransitions
import com.routinepilot.domain.schedule.ScheduleGenerator
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.database.ScheduledOccurrenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

class ScheduleRepositoryImpl @Inject constructor(
    private val database: RoutinePilotDatabase,
) : ScheduleRepository {
    private val scheduleDao = database.scheduleDao()
    private val occurrenceDao = database.scheduledOccurrenceDao()
    private val routineDao = database.routineDao()
    private val taskDao = database.taskDao()

    override fun observeSchedules(): Flow<List<Schedule>> =
        scheduleDao.observeActive().map { list -> list.map { it.toDomain() } }

    override fun observeSchedule(id: UUID): Flow<Schedule?> =
        scheduleDao.observeActiveById(id).map { it?.toDomain() }

    override fun observeAgenda(from: Instant, to: Instant): Flow<List<AgendaItem>> = combine(
        occurrenceDao.observeInRange(from, to),
        routineDao.observeActive(),
        taskDao.observeActive(),
    ) { occurrences, routines, tasks ->
        val routineTitles = routines.associate { it.id to it.title }
        val taskTitles = tasks.associate { it.id to it.title }
        occurrences.mapNotNull { it.toAgendaItem(routineTitles, taskTitles) }
    }

    override fun observeOccurrences(routineId: UUID): Flow<List<ScheduledOccurrence>> =
        occurrenceDao.observeActiveForRoutine(routineId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveSchedule(schedule: Schedule, now: Instant) {
        database.withTransaction {
            val saved = schedule.copy(modifiedAt = now)
            scheduleDao.upsert(saved.toEntity())
            regenerate(saved, now)
        }
    }

    override suspend fun deleteSchedule(id: UUID, now: Instant) {
        database.withTransaction {
            scheduleDao.markDeleted(id, now)
            occurrenceDao.deletePendingForSchedule(id)
        }
    }

    override suspend fun ensureOccurrences(now: Instant, zone: ZoneId): Int {
        var created = 0
        database.withTransaction {
            scheduleDao.activeForGeneration().forEach { entity ->
                var schedule = entity.toDomain()
                if (schedule.zone != zone) {
                    schedule = schedule.copy(zone = zone)
                    scheduleDao.updateZone(schedule.id, zone)
                }
                created += regenerate(schedule, now)
            }
        }
        return created
    }

    override suspend fun completeOccurrence(id: UUID, now: Instant) {
        transition(id, now) { OccurrenceTransitions.complete(it) }
    }

    override suspend fun skipOccurrence(id: UUID, now: Instant) {
        transition(id, now) { OccurrenceTransitions.skip(it) }
    }

    override suspend fun rescheduleOccurrence(id: UUID, newTime: Instant, zone: ZoneId, now: Instant) {
        transition(id, now) { OccurrenceTransitions.reschedule(it, newTime, zone) }
    }

    override suspend fun deleteOccurrence(id: UUID, now: Instant) {
        occurrenceDao.markDeleted(id, now)
    }

    /** Applies the planner's result. Rows it drops are removed; rows it adds are ignored if the slot exists. */
    private suspend fun regenerate(schedule: Schedule, now: Instant): Int {
        val existing = occurrenceDao.allForSchedule(schedule.id).map { it.toDomain() }
        val plan = ScheduleGenerator.plan(schedule, existing, now)
        if (plan.toRemove.isNotEmpty()) occurrenceDao.deleteByIds(plan.toRemove)
        if (plan.toInsert.isEmpty()) return 0
        val (routineId, taskId) = when (val target = schedule.target) {
            is ScheduleTarget.RoutineTarget -> target.routineId to null
            is ScheduleTarget.TaskTarget -> null to target.taskId
        }
        val rows = plan.toInsert.map { planned ->
            ScheduledOccurrenceEntity(
                id = UUID.randomUUID(),
                scheduleId = schedule.id,
                routineId = routineId,
                taskId = taskId,
                originalScheduledAt = planned.originalScheduledAt,
                scheduledAt = planned.originalScheduledAt,
                localDate = planned.localDate,
                status = OccurrenceStatus.PLANNED,
                createdAt = now,
                modifiedAt = now,
            )
        }
        return occurrenceDao.insertIgnoring(rows).count { it != IGNORED_ROW }
    }

    private suspend fun transition(id: UUID, now: Instant, change: (ScheduledOccurrence) -> ScheduledOccurrence?) {
        database.withTransaction<Unit> {
            val entity = occurrenceDao.findById(id) ?: return@withTransaction
            val updated = change(entity.toDomain()) ?: return@withTransaction
            occurrenceDao.upsert(updated.toEntity(modifiedAt = now))
        }
    }

    private fun ScheduledOccurrenceEntity.toAgendaItem(
        routineTitles: Map<UUID, String>,
        taskTitles: Map<UUID, String>,
    ): AgendaItem? {
        val titled = when {
            routineId != null -> routineTitles[routineId]?.let { it to AgendaKind.ROUTINE }
            taskId != null -> taskTitles[taskId]?.let { it to AgendaKind.TASK }
            else -> null
        } ?: return null
        return AgendaItem(
            occurrenceId = id,
            title = titled.first,
            kind = titled.second,
            scheduledAt = scheduledAt,
            originalScheduledAt = originalScheduledAt,
            status = status,
        )
    }

    private companion object {
        const val IGNORED_ROW = -1L
    }
}
