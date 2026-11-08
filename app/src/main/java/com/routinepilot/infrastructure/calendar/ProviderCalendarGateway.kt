package com.routinepilot.infrastructure.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import com.routinepilot.domain.calendar.CalendarEventDraft
import com.routinepilot.domain.calendar.CalendarGateway
import com.routinepilot.domain.calendar.CalendarInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The Android Calendar Provider behind [CalendarGateway]. Events are found by UID_2445, which holds the stable
 * identifier from [com.routinepilot.domain.calendar.CalendarEventMapper.uidFor]. Provider calls throw
 * SecurityException when a permission is missing. The synchronizer turns that into a permission state.
 */
class ProviderCalendarGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CalendarGateway {
        private val resolver = context.contentResolver

        override fun hasAccess(): Boolean =
            granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR)

        override fun writableCalendars(): List<CalendarInfo> =
            resolver
                .query(
                    Calendars.CONTENT_URI,
                    arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME),
                    "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1",
                    arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                    null,
                )?.use { cursor ->
                    val calendars = mutableListOf<CalendarInfo>()
                    while (cursor.moveToNext()) {
                        calendars +=
                            CalendarInfo(
                                id = cursor.getLong(0),
                                displayName = cursor.getString(1).orEmpty(),
                                accountName = cursor.getString(2).orEmpty(),
                            )
                    }
                    calendars
                }.orEmpty()

        override fun findEventId(
            calendarId: Long,
            uid: String,
        ): Long? =
            resolver
                .query(
                    Events.CONTENT_URI,
                    arrayOf(Events._ID),
                    "${Events.CALENDAR_ID} = ? AND ${Events.UID_2445} = ? AND ${Events.DELETED} = 0",
                    arrayOf(calendarId.toString(), uid),
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

        override fun eventExists(eventId: Long): Boolean =
            resolver
                .query(
                    Events.CONTENT_URI,
                    arrayOf(Events._ID),
                    "${Events._ID} = ? AND ${Events.DELETED} = 0",
                    arrayOf(eventId.toString()),
                    null,
                )?.use { it.moveToFirst() } == true

        override fun insertEvent(
            calendarId: Long,
            draft: CalendarEventDraft,
        ): Long? =
            resolver
                .insert(Events.CONTENT_URI, draft.toValues().apply { put(Events.CALENDAR_ID, calendarId) })
                ?.let { ContentUris.parseId(it) }

        override fun updateEvent(
            eventId: Long,
            draft: CalendarEventDraft,
        ): Boolean = resolver.update(eventUri(eventId), draft.toValues(), null, null) > 0

        override fun updateRecurrence(
            eventId: Long,
            rrule: String,
        ): Boolean =
            resolver.update(eventUri(eventId), ContentValues().apply { put(Events.RRULE, rrule) }, null, null) > 0

        override fun deleteEvent(eventId: Long): Boolean = resolver.delete(eventUri(eventId), null, null) > 0

        private fun granted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private fun eventUri(eventId: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)

        private fun CalendarEventDraft.toValues() =
            ContentValues().apply {
                put(Events.UID_2445, uid)
                put(Events.TITLE, title)
                put(Events.DESCRIPTION, description)
                put(Events.DTSTART, startMillis)
                put(Events.ALL_DAY, if (allDay) 1 else 0)
                put(Events.EVENT_TIMEZONE, timeZoneId)
                put(Events.DURATION, durationText)
                put(Events.RRULE, rrule)
            }
    }
