package com.routinepilot.presentation.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.domain.repository.StatsRepository
import com.routinepilot.domain.statistics.Statistics
import com.routinepilot.domain.statistics.StatisticsCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

private const val MINUTE_MILLIS = 60_000L
private const val STOP_TIMEOUT_MILLIS = 5_000L

data class StatsUiState(
    val loaded: Boolean = false,
    val statistics: Statistics? = null,
    val routineTitles: Map<UUID, String> = emptyMap(),
)

/**
 * Statistics for the Progress screen. Each emission comes from Room flows, so finishing a session or completing an
 * item updates the screen without a refresh. A one-minute tick rolls "today" over and keeps overdue items current.
 */
@HiltViewModel
class StatsViewModel
    @Inject
    constructor(
        statsRepository: StatsRepository,
        routineRepository: RoutineRepository,
        private val clock: Clock,
    ) : ViewModel() {
        val uiState: StateFlow<StatsUiState> =
            minuteTicks()
                .flatMapLatest { now ->
                    val today = LocalDate.ofInstant(now, clock.zone)
                    combine(
                        statsRepository.observeInput(
                            from = today.minusDays(StatisticsCalculator.LOOKBACK_DAYS - 1L),
                            to = today,
                            consistencyFrom = today.minusDays(StatisticsCalculator.CONSISTENCY_DAYS - 1L),
                            now = now,
                        ),
                        routineRepository.observeRoutines(),
                    ) { input, routines ->
                        StatsUiState(
                            loaded = true,
                            statistics = StatisticsCalculator.compute(input, today),
                            routineTitles = routines.associate { it.id to it.title },
                        )
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StatsUiState())

        private fun minuteTicks(): Flow<Instant> =
            flow {
                while (true) {
                    emit(Instant.now(clock))
                    delay(MINUTE_MILLIS)
                }
            }
    }
