package com.routinepilot.testing

import com.routinepilot.domain.calendar.CalendarEventDraft
import com.routinepilot.domain.calendar.CalendarGateway
import com.routinepilot.domain.calendar.CalendarInfo

/**
 * A mocked calendar provider. It holds events in memory and records every write, so tests can assert exactly what a
 * sync asked the provider to do. [access] false makes every call throw SecurityException, as a revoked permission does.
 */
class FakeCalendarGateway(
    var access: Boolean = true,
    var calendars: List<CalendarInfo> = listOf(CalendarInfo(1L, "Home", "me@example.com")),
) : CalendarGateway {
    data class StoredEvent(val calendarId: Long, val draft: CalendarEventDraft)

    val events = mutableMapOf<Long, StoredEvent>()
    val inserted = mutableListOf<Long>()
    val updated = mutableListOf<Long>()
    val recurrenceUpdates = mutableListOf<Pair<Long, String>>()
    val deleted = mutableListOf<Long>()
    private var nextId = 100L

    /** Revokes access after this many more provider calls, as if the user revoked it during a sync. */
    var revokeAfterCalls: Int? = null

    fun addExistingEvent(calendarId: Long, draft: CalendarEventDraft): Long {
        val id = nextId++
        events[id] = StoredEvent(calendarId, draft)
        return id
    }

    /** Simulates the user deleting an event in the calendar app. */
    fun deleteExternally(eventId: Long) {
        events.remove(eventId)
    }

    override fun hasAccess(): Boolean = access

    override fun writableCalendars(): List<CalendarInfo> {
        requireAccess()
        return calendars
    }

    override fun findEventId(calendarId: Long, uid: String): Long? {
        requireAccess()
        return events.entries.firstOrNull { it.value.calendarId == calendarId && it.value.draft.uid == uid }?.key
    }

    override fun eventExists(eventId: Long): Boolean {
        requireAccess()
        return events.containsKey(eventId)
    }

    override fun insertEvent(calendarId: Long, draft: CalendarEventDraft): Long? {
        requireAccess()
        val id = nextId++
        events[id] = StoredEvent(calendarId, draft)
        inserted += id
        return id
    }

    override fun updateEvent(eventId: Long, draft: CalendarEventDraft): Boolean {
        requireAccess()
        val existing = events[eventId] ?: return false
        events[eventId] = existing.copy(draft = draft)
        updated += eventId
        return true
    }

    override fun updateRecurrence(eventId: Long, rrule: String): Boolean {
        requireAccess()
        val existing = events[eventId] ?: return false
        events[eventId] = existing.copy(draft = existing.draft.copy(rrule = rrule))
        recurrenceUpdates += eventId to rrule
        return true
    }

    override fun deleteEvent(eventId: Long): Boolean {
        requireAccess()
        deleted += eventId
        return events.remove(eventId) != null
    }

    private fun requireAccess() {
        val remaining = revokeAfterCalls
        if (remaining != null) {
            if (remaining == 0) access = false
            revokeAfterCalls = remaining - 1
        }
        if (!access) throw SecurityException("Calendar permission revoked")
    }
}
