package com.routinepilot.data.repository

import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.infrastructure.database.RoutineDao
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoutineRepositoryImpl @Inject constructor(
    private val routineDao: RoutineDao,
) : RoutineRepository {
    override fun observeRoutines(): Flow<List<Routine>> =
        routineDao.observeAll().map { entities -> entities.map { Routine(it.id, it.title) } }
}
