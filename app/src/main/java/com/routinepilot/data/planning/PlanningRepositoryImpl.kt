package com.routinepilot.data.planning

import androidx.room.withTransaction
import com.routinepilot.data.mapping.toDomain
import com.routinepilot.domain.planning.FixedCommitment
import com.routinepilot.domain.planning.FlexibleTask
import com.routinepilot.domain.planning.MINUTES_PER_DAY
import com.routinepilot.domain.planning.PlanRequest
import com.routinepilot.domain.planning.PlanResult
import com.routinepilot.domain.planning.TimeRange
import com.routinepilot.domain.repository.PlanningRepository
import com.routinepilot.domain.repository.PlanningSettings
import com.routinepilot.domain.repository.PlanningTaskSetting
import com.routinepilot.infrastructure.database.PlannedBlockEntity
import com.routinepilot.infrastructure.database.PlanningDependencyEntity
import com.routinepilot.infrastructure.database.PlanningTaskEntity
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.database.TaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * Stores planning settings and the applied plan, and builds the planner's request for a day. A fixed item is a pending
 * occurrence of that day. A routine lasts as long as its steps together. A scheduled task uses its planning
 * duration. Anything
 * without a length uses [DEFAULT_MINUTES].
 */
class PlanningRepositoryImpl
    @Inject
    constructor(
        private val database: RoutinePilotDatabase,
        private val store: PlanningSettingsStore,
    ) : PlanningRepository {
        private val dao = database.planningDao()

        override fun observeSettings(): Flow<PlanningSettings> = store.observe()

        override suspend fun saveSettings(settings: PlanningSettings) {
            store.save(settings)
        }

        override fun observeTaskSettings(): Flow<List<PlanningTaskSetting>> =
            combine(dao.observeTasks(), dao.observeDependencies()) { tasks, dependencies ->
                val prerequisites = dependencies.groupBy({ it.taskId }, { it.dependsOnTaskId })
                tasks.map { row ->
                    PlanningTaskSetting(
                        taskId = row.taskId,
                        durationMinutes = row.durationMinutes,
                        priority = row.priority,
                        deadlineMinute = row.deadlineMinute,
                        dependsOn = prerequisites[row.taskId].orEmpty().toSet(),
                    )
                }
            }

        override suspend fun saveTaskSetting(setting: PlanningTaskSetting) {
            database.withTransaction {
                dao.upsertTask(
                    PlanningTaskEntity(
                        setting.taskId,
                        setting.durationMinutes,
                        setting.priority,
                        setting.deadlineMinute,
                    ),
                )
                dao.deletePrerequisitesOf(setting.taskId)
                dao.insertDependencies(setting.dependsOn.map { PlanningDependencyEntity(setting.taskId, it) })
            }
        }

        override suspend fun removeTaskSetting(taskId: UUID) {
            database.withTransaction {
                dao.deleteTask(taskId)
                dao.deleteDependenciesInvolving(taskId)
            }
        }

        override suspend fun buildRequest(
            date: LocalDate,
            zone: ZoneId,
            pins: Map<UUID, Int>,
        ): PlanRequest {
            val settings = store.current()
            val routineTitles =
                database
                    .routineDao()
                    .observeActive()
                    .first()
                    .associate { it.id to it.title }
            val tasks =
                database
                    .taskDao()
                    .observeActive()
                    .first()
                    .associateBy { it.id }
            val plannedRows = dao.tasks().associateBy { it.taskId }
            val fixed = fixedDay(date, zone, routineTitles, tasks, plannedRows)
            val prerequisites = dao.dependencies().groupBy({ it.taskId }, { it.dependsOnTaskId })
            val flexible =
                plannedRows.values.mapNotNull { row ->
                    val task = tasks[row.taskId] ?: return@mapNotNull null
                    if (task.isCompleted || row.taskId in fixed.taskIds) return@mapNotNull null
                    FlexibleTask(
                        id = row.taskId,
                        title = task.title,
                        durationMinutes = row.durationMinutes,
                        priority = row.priority,
                        deadlineMinute = row.deadlineMinute,
                        dependsOn = prerequisites[row.taskId].orEmpty().toSet(),
                    )
                }
            val previous = dao.blocksOn(date).filterNot { it.pinned }.associate { it.taskId to it.startMinute }
            return PlanRequest(
                workday = settings.workday,
                breaks = settings.breaks,
                commitments = fixed.commitments,
                tasks = flexible,
                pins = pins,
                previous = previous,
            )
        }

        override suspend fun storedPins(date: LocalDate): Map<UUID, Int> =
            dao.blocksOn(date).filter { it.pinned }.associate { it.taskId to it.startMinute }

        override suspend fun applyPlan(
            date: LocalDate,
            result: PlanResult,
        ) {
            check(result.canApply) { "A plan with unresolved overrides cannot be applied" }
            database.withTransaction {
                dao.clearBlocks(date)
                dao.insertBlocks(
                    result.placements.map {
                        PlannedBlockEntity(date, it.taskId, it.range.start, it.range.end, it.pinned)
                    },
                )
            }
        }

        private class FixedDay(
            val commitments: List<FixedCommitment>,
            val taskIds: Set<UUID>,
        )

        private suspend fun fixedDay(
            date: LocalDate,
            zone: ZoneId,
            routineTitles: Map<UUID, String>,
            tasks: Map<UUID, TaskEntity>,
            plannedRows: Map<UUID, PlanningTaskEntity>,
        ): FixedDay {
            val from = date.atStartOfDay(zone).toInstant()
            val to = date.plusDays(1).atStartOfDay(zone).toInstant()
            val pending =
                database
                    .scheduledOccurrenceDao()
                    .observeInRange(from, to)
                    .first()
                    .map { it.toDomain() }
                    .filter { it.status.isPending && it.deletedAt == null }
            val commitments = mutableListOf<FixedCommitment>()
            val taskIds = mutableSetOf<UUID>()
            for (occurrence in pending) {
                val local = ZonedDateTime.ofInstant(occurrence.scheduledAt, zone)
                val start = local.hour * MINUTES_PER_HOUR + local.minute
                val routineId = occurrence.routineId
                val taskId = occurrence.taskId
                when {
                    routineId != null ->
                        routineTitles[routineId]?.let { title ->
                            commitments += FixedCommitment(title, rangeFrom(start, routineMinutes(routineId)))
                        }
                    taskId != null ->
                        tasks[taskId]?.let { task ->
                            taskIds += taskId
                            val minutes = plannedRows[taskId]?.durationMinutes ?: DEFAULT_MINUTES
                            commitments += FixedCommitment(task.title, rangeFrom(start, minutes))
                        }
                }
            }
            return FixedDay(commitments, taskIds)
        }

        private suspend fun routineMinutes(routineId: UUID): Int {
            val seconds = database.routineStepDao().activeSnapshot(routineId).sumOf { it.durationSeconds ?: 0 }
            return if (seconds ==
                0
            ) {
                DEFAULT_MINUTES
            } else {
                maxOf(1, (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE)
            }
        }

        private fun rangeFrom(
            start: Int,
            minutes: Int,
        ): TimeRange = TimeRange(start, minOf(start + minutes, MINUTES_PER_DAY))

        private companion object {
            const val DEFAULT_MINUTES = 30
            const val MINUTES_PER_HOUR = 60
            const val SECONDS_PER_MINUTE = 60
        }
    }
