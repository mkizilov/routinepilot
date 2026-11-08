package com.routinepilot.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.calendar.CalendarInfo
import com.routinepilot.domain.calendar.CalendarSettings
import com.routinepilot.domain.calendar.CalendarState
import com.routinepilot.domain.calendar.LinkedEvent
import com.routinepilot.domain.repository.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

private const val STOP_TIMEOUT_MILLIS = 5_000L

data class CalendarSettingsUiState(
    val loaded: Boolean = false,
    val settings: CalendarSettings = CalendarSettings(),
    val hasAccess: Boolean = false,
    val calendars: List<CalendarInfo> = emptyList(),
    val syncState: CalendarState = CalendarState.OFF,
    val events: List<LinkedEvent> = emptyList(),
)

interface CalendarSettingsActions {
    fun refresh()

    fun setEnabled(enabled: Boolean)

    fun selectCalendar(calendar: CalendarInfo)

    fun setAllDay(allDay: Boolean)

    fun exportAgain(scheduleId: UUID)

    fun removeAllEvents()
}

private data class AccessSnapshot(
    val loaded: Boolean = false,
    val hasAccess: Boolean = false,
    val calendars: List<CalendarInfo> = emptyList(),
    val syncState: CalendarState = CalendarState.OFF,
)

/**
 * The calendar export screen. Settings and linked events come from Room and DataStore. Calendar access and the sync
 * state are read from the provider each time the screen resumes, because the user can change access outside the app.
 */
@HiltViewModel
class CalendarSettingsViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val clock: Clock,
) : ViewModel(), CalendarSettingsActions {
    private val snapshot = MutableStateFlow(AccessSnapshot())

    val uiState: StateFlow<CalendarSettingsUiState> = combine(
        calendarRepository.observeSettings(),
        calendarRepository.observeLinkedEvents(),
        snapshot,
    ) { settings, events, access ->
        CalendarSettingsUiState(
            loaded = access.loaded,
            settings = settings,
            hasAccess = access.hasAccess,
            calendars = access.calendars,
            syncState = access.syncState,
            events = events,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CalendarSettingsUiState())

    override fun refresh() {
        viewModelScope.launch {
            val hasAccess = calendarRepository.hasAccess()
            val calendars = calendarRepository.writableCalendars()
            val report = calendarRepository.sync(Instant.now(clock))
            snapshot.value = AccessSnapshot(
                loaded = true,
                hasAccess = hasAccess,
                calendars = calendars,
                syncState = report.state,
            )
        }
    }

    override fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    override fun selectCalendar(calendar: CalendarInfo) =
        update { it.copy(calendarId = calendar.id) }

    override fun setAllDay(allDay: Boolean) = update { it.copy(allDay = allDay) }

    override fun exportAgain(scheduleId: UUID) {
        viewModelScope.launch {
            calendarRepository.exportAgain(scheduleId, Instant.now(clock))
            refresh()
        }
    }

    override fun removeAllEvents() {
        viewModelScope.launch {
            calendarRepository.removeAllEvents()
            refresh()
        }
    }

    private fun update(change: (CalendarSettings) -> CalendarSettings) {
        viewModelScope.launch {
            calendarRepository.updateSettings(change)
            refresh()
        }
    }
}
