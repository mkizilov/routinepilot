package com.routinepilot.data.repository

import androidx.room.withTransaction
import com.routinepilot.data.mapping.toDomain
import com.routinepilot.data.mapping.toEntity
import com.routinepilot.data.sync.RoomOutboxStore
import com.routinepilot.data.sync.SyncFieldMapping
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.OfflineReplica
import com.routinepilot.domain.sync.SyncType
import com.routinepilot.domain.validation.RoutineValidator
import com.routinepilot.infrastructure.database.RoutineEntity
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class RoutineRepositoryImpl
    @Inject
    constructor(
        private val database: RoutinePilotDatabase,
    ) : RoutineRepository {
        private val routineDao = database.routineDao()
        private val stepDao = database.routineStepDao()
        private val replica = OfflineReplica(RoomOutboxStore(database.syncOutboxDao()))

        override fun observeRoutines(): Flow<List<Routine>> =
            routineDao.observeActive().map { routines -> routines.map { it.toDomain() } }

        override fun observeRoutine(id: UUID): Flow<Routine?> = routineDao.observeActiveById(id).map { it?.toDomain() }

        override fun observeSteps(routineId: UUID): Flow<List<RoutineStep>> =
            stepDao.observeActiveForRoutine(routineId).map { steps -> steps.map { it.toDomain() } }

        override suspend fun saveRoutine(
            routine: Routine,
            steps: List<RoutineStep>,
        ) {
            val now = Instant.now()
            database.withTransaction {
                val beforeRoutine = routineDao.findActive(routine.id)?.toDomain()
                val beforeSteps = stepDao.activeSnapshot(routine.id).map { it.toDomain() }.associateBy { it.id }
                routineDao.upsert(routine.toEntity(now))
                val kept = steps.map { it.id }.toSet()
                stepDao
                    .activeIds(routine.id)
                    .filterNot { it in kept }
                    .forEach { stepDao.markDeleted(it, now) }
                stepDao.upsert(steps.map { it.toEntity(now) })
                replica.record(
                    ObjectKey(SyncType.ROUTINE, routine.id),
                    beforeRoutine?.let { SyncFieldMapping.routine(it) },
                    SyncFieldMapping.routine(routine),
                    now.toEpochMilli(),
                )
                val afterSteps = steps.associateBy { it.id }
                (beforeSteps.keys + afterSteps.keys).forEach { stepId ->
                    replica.record(
                        ObjectKey(SyncType.ROUTINE_STEP, stepId),
                        beforeSteps[stepId]?.let { SyncFieldMapping.step(it) },
                        afterSteps[stepId]?.let { SyncFieldMapping.step(it) },
                        now.toEpochMilli(),
                    )
                }
            }
        }

        override suspend fun deleteRoutine(id: UUID) {
            val now = Instant.now()
            database.withTransaction {
                val beforeRoutine = routineDao.findActive(id)?.toDomain()
                val beforeSteps = stepDao.activeSnapshot(id).map { it.toDomain() }
                routineDao.markDeleted(id, now)
                stepDao.markDeletedForRoutine(id, now)
                replica.record(
                    ObjectKey(SyncType.ROUTINE, id),
                    beforeRoutine?.let { SyncFieldMapping.routine(it) },
                    null,
                    now.toEpochMilli(),
                )
                beforeSteps.forEach { step ->
                    replica.record(
                        ObjectKey(SyncType.ROUTINE_STEP, step.id),
                        SyncFieldMapping.step(step),
                        null,
                        now.toEpochMilli(),
                    )
                }
            }
        }

        override suspend fun restoreRoutine(id: UUID) {
            val now = Instant.now()
            database.withTransaction<Unit> {
                val routine = routineDao.findIncludingDeleted(id) ?: return@withTransaction
                val deletedAt = routine.deletedAt ?: return@withTransaction
                val restoredSteps = stepDao.deletedSnapshot(id, deletedAt).map { it.toDomain() }
                routineDao.restore(id, now)
                stepDao.restoreDeletedAt(id, deletedAt, now)
                replica.record(
                    ObjectKey(SyncType.ROUTINE, id),
                    null,
                    SyncFieldMapping.routine(routine.toDomain()),
                    now.toEpochMilli(),
                )
                restoredSteps.forEach { step ->
                    replica.record(
                        ObjectKey(SyncType.ROUTINE_STEP, step.id),
                        null,
                        SyncFieldMapping.step(step),
                        now.toEpochMilli(),
                    )
                }
            }
        }

        override suspend fun duplicateRoutine(id: UUID): UUID? {
            val now = Instant.now()
            return database.withTransaction<UUID?> {
                val source = routineDao.findActive(id) ?: return@withTransaction null
                val copyId = UUID.randomUUID()
                val copy =
                    RoutineEntity(
                        id = copyId,
                        title = "${source.title} (copy)".take(RoutineValidator.MAX_TITLE_LENGTH),
                        position = routineDao.countActive(),
                        createdAt = now,
                        modifiedAt = now,
                        description = source.description,
                    )
                routineDao.upsert(copy)
                replica.record(
                    ObjectKey(SyncType.ROUTINE, copyId),
                    null,
                    SyncFieldMapping.routine(copy.toDomain()),
                    now.toEpochMilli(),
                )
                val copiedSteps =
                    stepDao.activeSnapshot(id).map {
                        it.copy(
                            id = UUID.randomUUID(),
                            routineId = copyId,
                            createdAt = now,
                            modifiedAt = now,
                            deletedAt = null,
                        )
                    }
                stepDao.upsert(copiedSteps)
                copiedSteps.forEach { step ->
                    replica.record(
                        ObjectKey(SyncType.ROUTINE_STEP, step.id),
                        null,
                        SyncFieldMapping.step(step.toDomain()),
                        now.toEpochMilli(),
                    )
                }
                copyId
            }
        }

        override suspend fun deleteStep(id: UUID) {
            val now = Instant.now()
            database.withTransaction {
                val before = stepDao.findActive(id)?.toDomain()
                stepDao.markDeleted(id, now)
                replica.record(
                    ObjectKey(SyncType.ROUTINE_STEP, id),
                    before?.let { SyncFieldMapping.step(it) },
                    null,
                    now.toEpochMilli(),
                )
            }
        }
    }
