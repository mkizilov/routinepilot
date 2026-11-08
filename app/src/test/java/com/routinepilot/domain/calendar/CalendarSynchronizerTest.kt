package com.routinepilot.domain.calendar

import com.routinepilot.testing.FakeCalendarGateway
import com.routinepilot.testing.calendarSchedule
import com.routinepilot.testing.calendarScheduleId
import com.routinepilot.testing.calendarSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CalendarSynchronizerTest {
    private val now = Instant.parse("2026-03-10T12:00:00Z")
    private val enabled = CalendarSettings(enabled = true, calendarId = 1L)
    private val gateway = FakeCalendarGateway()
    private val synchronizer = CalendarSynchronizer(gateway)
    private val otherScheduleId = UUID.fromString("00000000-0000-4000-8000-0000000000cc")

    private fun syncAt(
        settings: CalendarSettings = enabled,
        sources: List<CalendarSource> = listOf(calendarSource()),
        links: List<CalendarLink> = emptyList(),
        at: Instant = now,
    ): CalendarSyncPlan = synchronizer.sync(settings, sources, links, at)

    @Test
    fun exportIsOffByDefault_andTouchesNothing() {
        val plan = syncAt(settings = CalendarSettings())

        assertEquals(CalendarState.OFF, plan.report.state)
        assertTrue(gateway.inserted.isEmpty())
    }

    @Test
    fun missingPermission_changesNothing() {
        gateway.access = false

        val plan = syncAt()

        assertEquals(CalendarState.PERMISSION_MISSING, plan.report.state)
        assertTrue(gateway.inserted.isEmpty())
    }

    @Test
    fun noDestination_changesNothing() {
        val plan = syncAt(settings = enabled.copy(calendarId = null))

        assertEquals(CalendarState.NO_DESTINATION, plan.report.state)
        assertTrue(gateway.inserted.isEmpty())
    }

    @Test
    fun destinationDeletedInSystemSettings_changesNothing() {
        val plan = syncAt(settings = enabled.copy(calendarId = 99L))

        assertEquals(CalendarState.DESTINATION_MISSING, plan.report.state)
        assertTrue(gateway.inserted.isEmpty())
    }

    @Test
    fun createsOneEventPerSchedule_andRecordsItsLink() {
        val plan = syncAt()

        val link = plan.upserts.single()
        assertEquals(1, gateway.inserted.size)
        assertEquals(gateway.inserted.single(), link.eventId)
        assertEquals(CalendarLinkStatus.LINKED, link.status)
        assertEquals(CalendarEventMapper.uidFor(calendarScheduleId), link.uid)
        assertEquals(
            CalendarEventMapper.uidFor(calendarScheduleId),
            gateway.events
                .getValue(link.eventId!!)
                .draft.uid,
        )
        assertEquals(1, plan.report.created)
    }

    @Test
    fun resyncWithStoredLinks_changesNothing() {
        val first = syncAt()

        val second = syncAt(links = first.upserts)

        assertTrue(second.upserts.isEmpty())
        assertTrue(gateway.updated.isEmpty())
        assertEquals(1, gateway.inserted.size)
    }

    @Test
    fun renamedRoutine_updatesTheSameEvent_withoutCreatingADuplicate() {
        val first = syncAt()
        val eventId = first.upserts.single().eventId!!

        val renamed = syncAt(sources = listOf(calendarSource(title = "Evening")), links = first.upserts)

        assertEquals(listOf(eventId), gateway.updated)
        assertEquals(1, gateway.events.size)
        assertEquals(
            "Evening",
            gateway.events
                .getValue(eventId)
                .draft.title,
        )
        assertEquals(1, renamed.report.updated)
    }

    @Test
    fun lostMapping_adoptsTheEventWithTheSameUid_insteadOfDuplicatingIt() {
        val existing = gateway.addExistingEvent(1L, CalendarEventMapper.draftFor(calendarSource(), false, null)!!)

        val plan = syncAt(links = emptyList())

        assertTrue(gateway.inserted.isEmpty())
        assertEquals(listOf(existing), gateway.updated)
        assertEquals(existing, plan.upserts.single().eventId)
        assertEquals(1, gateway.events.size)
    }

    @Test
    fun eventDeletedInTheCalendarApp_isMarkedRemoved_andNotRecreated() {
        val first = syncAt()
        gateway.deleteExternally(first.upserts.single().eventId!!)

        val afterDelete = syncAt(links = first.upserts)
        val later = syncAt(links = afterDelete.upserts)

        assertEquals(CalendarLinkStatus.REMOVED_IN_CALENDAR, afterDelete.upserts.single().status)
        assertTrue(later.upserts.isEmpty())
        assertEquals(1, gateway.inserted.size)
        assertEquals(1, later.report.removedInCalendar)
        assertTrue(gateway.events.isEmpty())
    }

    @Test
    fun removedLink_isExportedAgainOnlyAfterItIsForgotten() {
        val first = syncAt()
        gateway.deleteExternally(first.upserts.single().eventId!!)
        val removed = syncAt(links = first.upserts)

        val again = syncAt(links = emptyList())

        assertEquals(CalendarLinkStatus.REMOVED_IN_CALENDAR, removed.upserts.single().status)
        assertEquals(2, gateway.inserted.size)
        assertEquals(CalendarLinkStatus.LINKED, again.upserts.single().status)
    }

    @Test
    fun stoppedSchedule_endsItsSeriesAtTheStopTime_andKeepsPastEvents() {
        val first = syncAt()
        val eventId = first.upserts.single().eventId!!

        val stopped = syncAt(sources = emptyList(), links = first.upserts)
        val afterStop = syncAt(sources = emptyList(), links = stopped.upserts)

        val link = stopped.upserts.single()
        assertEquals(CalendarLinkStatus.ENDED, link.status)
        assertEquals("FREQ=DAILY;UNTIL=20260310T120000Z", link.rrule)
        assertEquals(listOf(eventId to "FREQ=DAILY;UNTIL=20260310T120000Z"), gateway.recurrenceUpdates)
        assertTrue(gateway.events.containsKey(eventId))
        assertEquals(1, stopped.report.ended)
        assertTrue(afterStop.upserts.isEmpty())
        assertEquals(1, gateway.recurrenceUpdates.size)
    }

    @Test
    fun stoppedBeforeTheFirstOccurrence_removesTheEvent() {
        val future = calendarSource(schedule = calendarSchedule(startDate = LocalDate.of(2026, 3, 12)))
        val first = syncAt(sources = listOf(future))
        val eventId = first.upserts.single().eventId!!

        val stopped = syncAt(sources = emptyList(), links = first.upserts)

        assertEquals(listOf(eventId), gateway.deleted)
        assertNull(stopped.upserts.single().eventId)
        assertEquals(CalendarLinkStatus.ENDED, stopped.upserts.single().status)
    }

    @Test
    fun changingTheDestination_removesTheOldEvents_andCreatesInTheNewCalendar() {
        val twoCalendars =
            FakeCalendarGateway(
                calendars =
                    listOf(
                        CalendarInfo(1L, "Home", "me@example.com"),
                        CalendarInfo(2L, "Work", "me@example.com"),
                    ),
            )
        val sync = CalendarSynchronizer(twoCalendars)
        val first = sync.sync(enabled, listOf(calendarSource()), emptyList(), now)
        val oldEvent = first.upserts.single().eventId!!

        val moved =
            sync.sync(
                enabled.copy(calendarId = 2L),
                listOf(calendarSource()),
                first.upserts,
                now,
            )

        assertEquals(listOf(oldEvent), twoCalendars.deleted)
        assertEquals(2L, moved.upserts.single().calendarId)
        assertEquals(listOf(2L), twoCalendars.events.values.map { it.calendarId })
    }

    @Test
    fun allDaySetting_rewritesTheEventAsAllDay_withoutMovingItsDate() {
        val first = syncAt()
        val eventId = first.upserts.single().eventId!!

        syncAt(settings = enabled.copy(allDay = true), links = first.upserts)

        val draft = gateway.events.getValue(eventId).draft
        assertEquals(listOf(eventId), gateway.updated)
        assertTrue(draft.allDay)
        assertEquals("UTC", draft.timeZoneId)
        assertEquals(Instant.parse("2026-03-10T00:00:00Z").toEpochMilli(), draft.startMillis)
    }

    @Test
    fun resaveOfAnUnchangedSchedule_onALaterDay_leavesThePastEventsInPlace() {
        val first = syncAt()
        val eventId = first.upserts.single().eventId!!
        val resaved = calendarSource(schedule = calendarSchedule(modifiedAt = Instant.parse("2026-03-20T08:00:00Z")))

        val second =
            syncAt(
                sources = listOf(resaved),
                links = first.upserts,
                at = Instant.parse("2026-03-20T09:00:00Z"),
            )

        assertTrue(second.upserts.isEmpty())
        assertTrue(gateway.updated.isEmpty())
        assertEquals(
            Instant.parse("2026-03-10T08:00:00Z").toEpochMilli(),
            gateway.events
                .getValue(eventId)
                .draft.startMillis,
        )
    }

    @Test
    fun permissionRevokedDuringSync_reportsPermissionMissing_andChangesNoLinks() {
        gateway.revokeAfterCalls = 1

        val plan = syncAt()

        assertEquals(CalendarState.PERMISSION_MISSING, plan.report.state)
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun permissionRevokedAfterAnInsert_isRecoveredByAdoption_withoutADuplicate() {
        val morning = calendarSource()
        val evening = calendarSource(schedule = calendarSchedule().copy(id = otherScheduleId))
        gateway.revokeAfterCalls = 3

        val interrupted = syncAt(sources = listOf(morning, evening))

        assertEquals(CalendarState.PERMISSION_MISSING, interrupted.report.state)
        assertTrue(interrupted.upserts.isEmpty())
        assertEquals(1, gateway.events.size)

        gateway.access = true
        gateway.revokeAfterCalls = null
        val retried = syncAt(sources = listOf(morning, evening))

        assertEquals(2, retried.upserts.size)
        assertEquals(2, gateway.events.size)
        assertEquals(1, retried.report.updated)
        assertEquals(1, retried.report.created)
    }

    @Test
    fun removeAll_deletesEveryLinkedEvent_andForgetsTheLinks() {
        val first = syncAt()
        val eventId = first.upserts.single().eventId!!

        val removal = synchronizer.removeAll(first.upserts)

        assertEquals(CalendarState.SYNCED, removal.report.state)
        assertEquals(1, removal.report.removed)
        assertEquals(listOf(calendarScheduleId), removal.deletes)
        assertTrue(gateway.events.isEmpty())
        assertEquals(listOf(eventId), gateway.deleted)
    }

    @Test
    fun removeAll_withoutPermission_leavesEventsAndLinksInPlace() {
        val first = syncAt()
        gateway.access = false

        val removal = synchronizer.removeAll(first.upserts)

        assertEquals(CalendarState.PERMISSION_MISSING, removal.report.state)
        assertTrue(removal.deletes.isEmpty())
        assertEquals(1, gateway.events.size)
    }
}
