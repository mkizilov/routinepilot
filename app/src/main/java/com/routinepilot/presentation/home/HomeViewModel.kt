package com.routinepilot.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.repository.RoutineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(val routines: List<Routine> = emptyList())

@HiltViewModel
class HomeViewModel @Inject constructor(
    routineRepository: RoutineRepository,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = routineRepository.observeRoutines()
        .map { HomeUiState(routines = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = HomeUiState(),
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
