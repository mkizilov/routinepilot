package com.routinepilot.domain.repository

import com.routinepilot.domain.model.Routine
import kotlinx.coroutines.flow.Flow

interface RoutineRepository {
    fun observeRoutines(): Flow<List<Routine>>
}
