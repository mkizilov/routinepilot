package com.routinepilot.domain.repository

import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface RoutineRepository {
    fun observeRoutines(): Flow<List<Routine>>

    fun observeRoutine(id: UUID): Flow<Routine?>

    fun observeSteps(routineId: UUID): Flow<List<RoutineStep>>

    /**
     * Saves the routine and makes [steps] its complete set of active steps in one transaction.
     * Active steps missing from [steps] are soft-deleted.
     */
    suspend fun saveRoutine(routine: Routine, steps: List<RoutineStep>)

    /** Soft-deletes the routine and its steps. [restoreRoutine] undoes it. */
    suspend fun deleteRoutine(id: UUID)

    /** Restores a soft-deleted routine and the steps removed along with it. */
    suspend fun restoreRoutine(id: UUID)

    /** Copies an active routine and its steps as new rows. Returns the new id, or null if the source is missing. */
    suspend fun duplicateRoutine(id: UUID): UUID?

    suspend fun deleteStep(id: UUID)
}
