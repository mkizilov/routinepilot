package com.routinepilot.domain.calendar

import com.routinepilot.domain.model.Schedule
import java.time.Instant
import java.util.UUID

/**
 * Brings the calendar in line with the schedules. It only ever touches events that it can identify, through a stored
 * mapping or the stable UID, and it never deletes an event unless a rule below says so.
 *
 * Rules:
 * - Each active schedule has one event. If its mapping is live, the event is updated only when its content hash has
 *   changed. Otherwise the event with the same UID is adopted, or a new one is inserted. Duplicates are not created.
 * - An event deleted in the calendar app is marked REMOVED_IN_CALENDAR and is not recreated until the user asks.
 * - A stopped schedule ends its series at the stop time, so past events stay. A series that would have no occurrence
 *   left is removed, because nothing would ever show.
 * - Changing the destination removes the previous calendar's events. The UI confirms this before the change.
 * - If calendar permission is missing, or the destination no longer exists, nothing changes and the state says why.
 *
 * Permission can also be revoked during a sync, and then the gateway throws SecurityException. The result is the same
 * as a missing permission: the plan is empty, so the caller keeps every link it had. Events written before the
 * revocation carry their UID, so the next sync adopts them instead of creating duplicates.
 */
class CalendarSynchronizer(
    private val gateway: CalendarGateway,
) {
    fun sync(
        settings: CalendarSettings,
        sources: List<CalendarSource>,
        links: List<CalendarLink>,
        now: Instant,
    ): CalendarSyncPlan =
        try {
            plan(settings, sources, links, now)
        } catch (expectedPermissionLoss: SecurityException) {
            CalendarSyncPlan.stopped(CalendarState.PERMISSION_MISSING)
        }

    /** Removes the events the user asked to remove. Works when integration is off, because the user asked for it. */
    fun removeAll(links: List<CalendarLink>): CalendarSyncPlan {
        if (!gateway.hasAccess()) return CalendarSyncPlan.stopped(CalendarState.PERMISSION_MISSING)
        return try {
            val removed = links.count { link -> link.eventId?.let { gateway.deleteEvent(it) } == true }
            CalendarSyncPlan(
                upserts = emptyList(),
                deletes = links.map { it.scheduleId },
                report = CalendarSyncReport(CalendarState.SYNCED, removed = removed),
            )
        } catch (expectedPermissionLoss: SecurityException) {
            CalendarSyncPlan.stopped(CalendarState.PERMISSION_MISSING)
        }
    }

    private fun plan(
        settings: CalendarSettings,
        sources: List<CalendarSource>,
        links: List<CalendarLink>,
        now: Instant,
    ): CalendarSyncPlan {
        if (!settings.enabled) return CalendarSyncPlan.stopped(CalendarState.OFF)
        if (!gateway.hasAccess()) return CalendarSyncPlan.stopped(CalendarState.PERMISSION_MISSING)
        val destination = settings.calendarId ?: return CalendarSyncPlan.stopped(CalendarState.NO_DESTINATION)
        if (gateway.writableCalendars().none { it.id == destination }) {
            return CalendarSyncPlan.stopped(CalendarState.DESTINATION_MISSING)
        }

        val (previous, current) = links.partition { it.calendarId != destination }
        val planning = Planning(destination, settings.allDay, now)
        planning.removePrevious(previous)
        planning.writeSources(sources, current)
        planning.endInactive(current, sources)
        return planning.toPlan(current)
    }

    private fun endSeries(
        link: CalendarLink,
        now: Instant,
    ): CalendarLink {
        val eventId = link.eventId
        return when {
            eventId == null || !gateway.eventExists(eventId) ->
                link.copy(status = CalendarLinkStatus.ENDED, eventId = null, updatedAt = now)
            CalendarEventMapper.endsBeforeFirstOccurrence(link.startMillis, now, link.allDay) -> {
                gateway.deleteEvent(eventId)
                link.copy(status = CalendarLinkStatus.ENDED, eventId = null, updatedAt = now)
            }
            else -> {
                val rule = CalendarEventMapper.endedRule(link.rrule, now, link.allDay)
                gateway.updateRecurrence(eventId, rule)
                link.copy(status = CalendarLinkStatus.ENDED, rrule = rule, updatedAt = now)
            }
        }
    }

    /** The writes of one sync pass, collected in the order they must reach the calendar. */
    private inner class Planning(
        private val destination: Long,
        private val allDay: Boolean,
        private val now: Instant,
    ) {
        private val upserts = mutableListOf<CalendarLink>()
        private val deletes = mutableListOf<UUID>()
        private var created = 0
        private var updated = 0
        private var ended = 0

        fun removePrevious(previous: List<CalendarLink>) {
            for (link in previous) {
                link.eventId?.let { gateway.deleteEvent(it) }
                deletes += link.scheduleId
            }
        }

        fun writeSources(
            sources: List<CalendarSource>,
            current: List<CalendarLink>,
        ) {
            val currentById = current.associateBy { it.scheduleId }
            for (source in sources) {
                val link = currentById[source.schedule.id]
                CalendarEventMapper.draftFor(source, allDay, link)?.let { draft ->
                    writeSource(source, link, draft)
                }
            }
        }

        private fun writeSource(
            source: CalendarSource,
            link: CalendarLink?,
            draft: CalendarEventDraft,
        ) {
            val hash = CalendarEventMapper.contentHash(draft)
            if (link?.status == CalendarLinkStatus.REMOVED_IN_CALENDAR) return
            val liveLink = link?.takeIf { it.status == CalendarLinkStatus.LINKED && it.eventId != null }
            val liveEventId = liveLink?.eventId
            if (liveLink != null && liveEventId != null) {
                updateLinked(source, liveLink, liveEventId, draft, hash)
            } else {
                insertOrAdopt(source, draft, hash)
            }
        }

        /** An event this app already created. It is updated only when its content changed, and never recreated. */
        private fun updateLinked(
            source: CalendarSource,
            link: CalendarLink,
            eventId: Long,
            draft: CalendarEventDraft,
            hash: String,
        ) {
            if (!gateway.eventExists(eventId)) {
                upserts += link.copy(status = CalendarLinkStatus.REMOVED_IN_CALENDAR, updatedAt = now)
                return
            }
            if (link.contentHash == hash) return
            gateway.updateEvent(eventId, draft)
            upserts += linkFor(source.schedule, eventId, draft, hash)
            updated++
        }

        /** No live mapping: adopt the event with the same UID if there is one, otherwise insert a new event. */
        private fun insertOrAdopt(
            source: CalendarSource,
            draft: CalendarEventDraft,
            hash: String,
        ) {
            val existingId = gateway.findEventId(destination, draft.uid)
            val eventId =
                if (existingId != null) {
                    gateway.updateEvent(existingId, draft)
                    existingId
                } else {
                    gateway.insertEvent(destination, draft) ?: return
                }
            upserts += linkFor(source.schedule, eventId, draft, hash)
            if (existingId == null) created++ else updated++
        }

        /** Links that no source covers any more: removed when the user deleted them, otherwise their series ends. */
        fun endInactive(
            current: List<CalendarLink>,
            sources: List<CalendarSource>,
        ) {
            val activeIds = sources.mapTo(mutableSetOf()) { it.schedule.id }
            for (link in current) {
                if (link.scheduleId in activeIds) continue
                when (link.status) {
                    CalendarLinkStatus.REMOVED_IN_CALENDAR -> deletes += link.scheduleId
                    CalendarLinkStatus.ENDED -> Unit
                    CalendarLinkStatus.LINKED -> {
                        upserts += endSeries(link, now)
                        ended++
                    }
                }
            }
        }

        fun toPlan(current: List<CalendarLink>): CalendarSyncPlan {
            val touched = upserts.mapTo(mutableSetOf()) { it.scheduleId } + deletes
            val finalLinks = current.filter { it.scheduleId !in touched } + upserts
            return CalendarSyncPlan(
                upserts = upserts,
                deletes = deletes,
                report =
                    CalendarSyncReport(
                        state = CalendarState.SYNCED,
                        linked = finalLinks.count { it.status == CalendarLinkStatus.LINKED },
                        created = created,
                        updated = updated,
                        ended = ended,
                        removedInCalendar = finalLinks.count { it.status == CalendarLinkStatus.REMOVED_IN_CALENDAR },
                    ),
            )
        }

        private fun linkFor(
            schedule: Schedule,
            eventId: Long,
            draft: CalendarEventDraft,
            hash: String,
        ) = CalendarLink(
            scheduleId = schedule.id,
            calendarId = destination,
            eventId = eventId,
            uid = draft.uid,
            allDay = draft.allDay,
            rrule = draft.rrule,
            startMillis = draft.startMillis,
            ruleKey = CalendarEventMapper.ruleKeyFor(schedule),
            contentHash = hash,
            status = CalendarLinkStatus.LINKED,
            updatedAt = now,
        )
    }
}
