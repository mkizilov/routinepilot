package com.routinepilot.domain.repository

import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface RoutineRepository {
    fun observeRoutines(): Flow<List<Routine>>

    fun observeRoutine(id: UUID): Flow<Routine?>

    fun observeSteps(routineId: UUID): Flow<List<RoutineStep>>

    /** Saves the routine and upserts the given steps in one transaction. */
    suspend fun saveRoutine(routine: Routine, steps: List<RoutineStep>)

    /** Soft-deletes the routine and its steps. */
    suspend fun deleteRoutine(id: UUID)

    suspend fun deleteStep(id: UUID)
}
