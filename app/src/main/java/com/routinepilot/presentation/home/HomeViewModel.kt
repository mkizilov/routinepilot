package com.routinepilot.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.execution.ActiveExecutionStore
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.repository.RoutineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class HomeUiState(
    val routines: List<Routine> = emptyList(),
    val activeRoutineId: UUID? = null,
)

sealed interface HomeEvent {
    data class RoutineDeleted(
        val id: UUID,
    ) : HomeEvent

    data object RoutineDuplicated : HomeEvent
}

interface HomeActions {
    fun duplicateRoutine(id: UUID)

    fun deleteRoutine(id: UUID)
}

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val routineRepository: RoutineRepository,
        activeExecutionStore: ActiveExecutionStore,
    ) : ViewModel(),
        HomeActions {
        val uiState: StateFlow<HomeUiState> =
            combine(
                routineRepository.observeRoutines(),
                activeExecutionStore.observe(),
            ) { routines, active -> HomeUiState(routines = routines, activeRoutineId = active?.routineId) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = HomeUiState(),
                )

        private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)
        val events: Flow<HomeEvent> = eventChannel.receiveAsFlow()

        override fun duplicateRoutine(id: UUID) {
            viewModelScope.launch {
                routineRepository.duplicateRoutine(id)
                eventChannel.send(HomeEvent.RoutineDuplicated)
            }
        }

        override fun deleteRoutine(id: UUID) {
            viewModelScope.launch {
                routineRepository.deleteRoutine(id)
                eventChannel.send(HomeEvent.RoutineDeleted(id))
            }
        }

        fun undoDelete(id: UUID) {
            viewModelScope.launch { routineRepository.restoreRoutine(id) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
