package com.routinepilot.domain.calendar

import com.routinepilot.domain.model.Schedule
import java.time.Instant
import java.util.UUID

/** A calendar the user can write to. Read-only calendars are never offered. */
data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
)

enum class CalendarLinkStatus {
    /** The event exists and follows the schedule. */
    LINKED,

    /** The schedule was stopped. Its series ended at the stop time, and the event is no longer updated. */
    ENDED,

    /** The event was deleted in the calendar app. It is not recreated until the user asks for it. */
    REMOVED_IN_CALENDAR,
}

/**
 * The mapping from a schedule to its calendar event. [uid] is the stable external identifier, so a lost mapping can be
 * rebuilt by finding the same event. [rrule] and [startMillis] are kept so an ended series can be closed without
 * rebuilding the event. [ruleKey] records the rule the series was built from, so the series keeps its start while that
 * rule is unchanged. [contentHash] tells whether an update would change anything.
 */
data class CalendarLink(
    val scheduleId: UUID,
    val calendarId: Long,
    val eventId: Long?,
    val uid: String,
    val allDay: Boolean,
    val rrule: String,
    val startMillis: Long,
    val ruleKey: String,
    val contentHash: String,
    val status: CalendarLinkStatus,
    val updatedAt: Instant,
)

/** A link as the user sees it. [title] is null once the schedule has stopped. */
data class LinkedEvent(
    val scheduleId: UUID,
    val title: String?,
    val status: CalendarLinkStatus,
    val eventId: Long?,
)

data class CalendarSettings(
    val enabled: Boolean = false,
    val calendarId: Long? = null,
    val allDay: Boolean = false,
    val lastSyncMillis: Long? = null,
)

data class CalendarStep(val title: String, val durationSeconds: Int?)

/** A schedule to export. [title] is the routine or task name, and [steps] is empty for tasks. */
data class CalendarSource(
    val schedule: Schedule,
    val title: String,
    val isTask: Boolean,
    val steps: List<CalendarStep>,
)

/** Everything the calendar needs for one event. Field names follow the Calendar Provider's columns. */
data class CalendarEventDraft(
    val uid: String,
    val title: String,
    val description: String,
    val timeZoneId: String,
    val allDay: Boolean,
    val startMillis: Long,
    val durationText: String,
    val rrule: String,
)

enum class CalendarState {
    OFF,
    PERMISSION_MISSING,
    NO_DESTINATION,
    DESTINATION_MISSING,
    SYNCED,
}

data class CalendarSyncReport(
    val state: CalendarState,
    val linked: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val ended: Int = 0,
    val removedInCalendar: Int = 0,
    val removed: Int = 0,
)

/**
 * What a sync decided. The caller applies [deletes] before [upserts], so a schedule whose calendar changed gets its
 * old mapping removed and its new one stored.
 */
data class CalendarSyncPlan(
    val upserts: List<CalendarLink>,
    val deletes: List<UUID>,
    val report: CalendarSyncReport,
) {
    companion object {
        fun stopped(state: CalendarState) = CalendarSyncPlan(emptyList(), emptyList(), CalendarSyncReport(state))
    }
}

/**
 * The calendar provider, behind an interface so the sync rules can be tested with a mocked provider. Methods may
 * throw SecurityException if calendar permission is revoked while they run. The caller treats that as permission loss.
 */
interface CalendarGateway {
    fun hasAccess(): Boolean

    fun writableCalendars(): List<CalendarInfo>

    /** The id of the event with this stable identifier in the calendar, or null if there is none. */
    fun findEventId(calendarId: Long, uid: String): Long?

    fun eventExists(eventId: Long): Boolean

    /** Returns the new event's id, or null if the provider refused. */
    fun insertEvent(calendarId: Long, draft: CalendarEventDraft): Long?

    fun updateEvent(eventId: Long, draft: CalendarEventDraft): Boolean

    fun updateRecurrence(eventId: Long, rrule: String): Boolean

    fun deleteEvent(eventId: Long): Boolean
}
