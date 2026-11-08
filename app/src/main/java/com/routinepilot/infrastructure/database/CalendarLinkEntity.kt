package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.routinepilot.domain.calendar.CalendarLinkStatus
import java.time.Instant
import java.util.UUID

/**
 * The device-local link between a schedule and its calendar event. Event ids mean nothing on another device, so the
 * table is not synced. A link is deleted outright, not tombstoned, once the event it describes is gone.
 */
@Entity(tableName = "calendar_links")
data class CalendarLinkEntity(
    @PrimaryKey val scheduleId: UUID,
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
