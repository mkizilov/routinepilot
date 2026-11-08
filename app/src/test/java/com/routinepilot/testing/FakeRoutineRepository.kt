package com.routinepilot.testing

import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.domain.repository.RoutineRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/** In-memory repository with the same soft-delete and replace-on-save semantics as the Room implementation. */
class FakeRoutineRepository(
    initialRoutines: List<Routine> = emptyList(),
    initialSteps: List<RoutineStep> = emptyList(),
) : RoutineRepository {
    val routines = MutableStateFlow(initialRoutines)
    val steps = MutableStateFlow(initialSteps)
    private val deleted = mutableMapOf<UUID, Pair<Routine, List<RoutineStep>>>()

    override fun observeRoutines(): Flow<List<Routine>> =
        routines.map { list -> list.sortedWith(compareBy({ it.position }, { it.id.toString() })) }

    override fun observeRoutine(id: UUID): Flow<Routine?> = routines.map { list -> list.firstOrNull { it.id == id } }

    override fun observeSteps(routineId: UUID): Flow<List<RoutineStep>> =
        steps.map { list -> list.filter { it.routineId == routineId }.sortedBy { it.position } }

    override suspend fun saveRoutine(
        routine: Routine,
        steps: List<RoutineStep>,
    ) {
        routines.update { list -> list.filterNot { it.id == routine.id } + routine }
        this.steps.update { current -> current.filterNot { it.routineId == routine.id } + steps }
    }

    override suspend fun deleteRoutine(id: UUID) {
        val routine = routines.value.firstOrNull { it.id == id } ?: return
        val ownSteps = steps.value.filter { it.routineId == id }
        deleted[id] = routine to ownSteps
        routines.update { list -> list.filterNot { it.id == id } }
        steps.update { list -> list.filterNot { it.routineId == id } }
    }

    override suspend fun restoreRoutine(id: UUID) {
        val (routine, ownSteps) = deleted.remove(id) ?: return
        routines.update { it + routine }
        steps.update { it + ownSteps }
    }

    override suspend fun duplicateRoutine(id: UUID): UUID? {
        val source = routines.value.firstOrNull { it.id == id } ?: return null
        val copyId = UUID.randomUUID()
        val now = Instant.now()
        routines.update {
            it + source.copy(id = copyId, title = "${source.title} (copy)", position = it.size, createdAt = now)
        }
        val copies =
            steps.value
                .filter { it.routineId == id }
                .map { it.copy(id = UUID.randomUUID(), routineId = copyId) }
        steps.update { it + copies }
        return copyId
    }

    override suspend fun deleteStep(id: UUID) {
        steps.update { list -> list.filterNot { it.id == id } }
    }
}
