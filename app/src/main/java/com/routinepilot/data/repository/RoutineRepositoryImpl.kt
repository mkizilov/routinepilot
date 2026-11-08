package com.routinepilot.data.repository

import androidx.room.withTransaction
import com.routinepilot.data.mapping.toDomain
import com.routinepilot.data.mapping.toEntity
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoutineRepositoryImpl @Inject constructor(
    private val database: RoutinePilotDatabase,
) : RoutineRepository {
    private val routineDao = database.routineDao()
    private val stepDao = database.routineStepDao()

    override fun observeRoutines(): Flow<List<Routine>> =
        routineDao.observeActive().map { routines -> routines.map { it.toDomain() } }

    override fun observeRoutine(id: UUID): Flow<Routine?> =
        routineDao.observeActiveById(id).map { it?.toDomain() }

    override fun observeSteps(routineId: UUID): Flow<List<RoutineStep>> =
        stepDao.observeActiveForRoutine(routineId).map { steps -> steps.map { it.toDomain() } }

    override suspend fun saveRoutine(routine: Routine, steps: List<RoutineStep>) {
        val now = Instant.now()
        database.withTransaction {
            routineDao.upsert(routine.toEntity(now))
            stepDao.upsert(steps.map { it.toEntity(now) })
        }
    }

    override suspend fun deleteRoutine(id: UUID) {
        val now = Instant.now()
        database.withTransaction {
            routineDao.markDeleted(id, now)
            stepDao.markDeletedForRoutine(id, now)
        }
    }

    override suspend fun deleteStep(id: UUID) {
        stepDao.markDeleted(id, Instant.now())
    }
}
