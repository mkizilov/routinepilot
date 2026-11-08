package com.routinepilot.domain.repository

import com.routinepilot.domain.calendar.CalendarInfo
import com.routinepilot.domain.calendar.CalendarSettings
import com.routinepilot.domain.calendar.CalendarSyncReport
import com.routinepilot.domain.calendar.LinkedEvent
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

/** Optional calendar export. Nothing here runs unless the user turns export on. */
interface CalendarRepository {
    fun observeSettings(): Flow<CalendarSettings>

    /** Stores the settings. The background observer syncs when the fields that affect export change. */
    suspend fun updateSettings(change: (CalendarSettings) -> CalendarSettings)

    fun observeLinkedEvents(): Flow<List<LinkedEvent>>

    fun hasAccess(): Boolean

    /** Empty when calendar access is missing or was revoked just now. */
    suspend fun writableCalendars(): List<CalendarInfo>

    /** Brings the calendar in line with the schedules. Calls run one at a time. */
    suspend fun sync(now: Instant): CalendarSyncReport

    /** Deletes every linked event and turns export off. Nothing changes if calendar access is missing. */
    suspend fun removeAllEvents(): CalendarSyncReport

    /** Forgets the link to an event the user deleted, then exports the schedule again as a new event. */
    suspend fun exportAgain(
        scheduleId: UUID,
        now: Instant,
    ): CalendarSyncReport
}
