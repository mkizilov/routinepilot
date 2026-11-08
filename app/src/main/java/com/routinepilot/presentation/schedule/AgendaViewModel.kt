package com.routinepilot.presentation.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.repository.ScheduleRepository
import com.routinepilot.domain.schedule.Agenda
import com.routinepilot.domain.schedule.AgendaBuilder
import com.routinepilot.domain.schedule.ScheduleCalendar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

private const val MINUTE_MILLIS = 60_000L
private const val POSTPONE_MINUTES = 15L

data class AgendaUiState(
    val loaded: Boolean = false,
    val date: LocalDate? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val agenda: Agenda = Agenda(emptyList(), emptyList(), emptyList()),
)

interface AgendaActions {
    fun complete(occurrenceId: UUID)

    fun skip(occurrenceId: UUID)

    /** Moves a pending occurrence [POSTPONE_MINUTES] later, keeping the exact instant so the move is unambiguous. */
    fun postpone(
        occurrenceId: UUID,
        currentTime: Instant,
    )
}

/**
 * Today's agenda. Sections are recomputed every minute so "Due now" stays current without a manual refresh. Generation
 * runs on open, so the day is planned even if the periodic job has not run yet.
 */
@HiltViewModel
class AgendaViewModel
    @Inject
    constructor(
        private val scheduleRepository: ScheduleRepository,
        private val clock: Clock,
    ) : ViewModel(),
        AgendaActions {
        val uiState: StateFlow<AgendaUiState> =
            minuteTicks()
                .flatMapLatest { now -> agendaAt(now) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, AgendaUiState())

        init {
            viewModelScope.launch { scheduleRepository.ensureOccurrences(Instant.now(clock), clock.zone) }
        }

        override fun complete(occurrenceId: UUID) = runNow { scheduleRepository.completeOccurrence(occurrenceId, it) }

        override fun skip(occurrenceId: UUID) = runNow { scheduleRepository.skipOccurrence(occurrenceId, it) }

        override fun postpone(
            occurrenceId: UUID,
            currentTime: Instant,
        ) {
            val zone = clock.zone
            runNow {
                val target = currentTime.plus(Duration.ofMinutes(POSTPONE_MINUTES))
                scheduleRepository.rescheduleOccurrence(occurrenceId, target, zone, it)
            }
        }

        private fun minuteTicks(): Flow<Instant> =
            flow {
                while (true) {
                    emit(Instant.now(clock))
                    delay(MINUTE_MILLIS)
                }
            }

        private fun agendaAt(now: Instant): Flow<AgendaUiState> {
            val zone = clock.zone
            val today = ScheduleCalendar.dateOf(now, zone)
            return scheduleRepository
                .observeAgenda(
                    from = ScheduleCalendar.startOfDay(today, zone),
                    to = ScheduleCalendar.startOfDay(today.plusDays(1), zone),
                ).map { items ->
                    AgendaUiState(loaded = true, date = today, zone = zone, agenda = AgendaBuilder.build(items, now))
                }
        }

        private fun runNow(action: suspend (Instant) -> Unit) {
            viewModelScope.launch { action(Instant.now(clock)) }
        }
    }
