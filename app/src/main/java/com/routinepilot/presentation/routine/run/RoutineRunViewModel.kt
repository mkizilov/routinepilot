package com.routinepilot.presentation.routine.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.data.execution.RoutineRunCoordinator
import com.routinepilot.domain.execution.RunSnapshot
import com.routinepilot.domain.execution.TimerState
import com.routinepilot.presentation.routine.editor.ROUTINE_ID_ARG
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import javax.inject.Inject

data class RoutineRunUiState(
    val routineTitle: String = "",
    val timer: TimerState = TimerState(),
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val blockedByOtherRun: Boolean = false,
    val isClosed: Boolean = false,
    val foregroundUnavailable: Boolean = false,
)

interface RoutineRunActions {
    fun start()

    fun pause()

    fun resume()

    fun skip()

    fun previous()

    fun finish()

    fun cancel()
}

/**
 * Thin view over [RoutineRunCoordinator]. The run keeps going whether or not this screen exists, so leaving the
 * screen never pauses it.
 */
@HiltViewModel
class RoutineRunViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val coordinator: RoutineRunCoordinator,
) : ViewModel(), RoutineRunActions {
    private val routineId: UUID = UUID.fromString(checkNotNull(savedStateHandle.get<String>(ROUTINE_ID_ARG)))

    val uiState: StateFlow<RoutineRunUiState> = coordinator.snapshot
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RoutineRunUiState())

    init {
        coordinator.open(routineId)
    }

    override fun start() = coordinator.start()

    override fun pause() = coordinator.pause()

    override fun resume() = coordinator.resume()

    override fun skip() = coordinator.skip()

    override fun previous() = coordinator.previous()

    override fun finish() = coordinator.finish()

    override fun cancel() = coordinator.cancel()

    fun onForeground() = coordinator.onForeground()

    fun onBackground() = coordinator.onBackground()
}

/** While another routine is running this screen shows no timer and no title, so it cannot be confused with that run. */
private fun RunSnapshot.toUiState() = RoutineRunUiState(
    routineTitle = if (blockedByOtherRun) "" else routineTitle,
    timer = if (blockedByOtherRun) TimerState() else timer,
    isLoading = isLoading,
    isMissing = isMissing,
    blockedByOtherRun = blockedByOtherRun,
    isClosed = isClosed,
    foregroundUnavailable = foregroundUnavailable,
)
