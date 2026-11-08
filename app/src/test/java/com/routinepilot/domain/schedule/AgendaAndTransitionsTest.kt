package com.routinepilot.domain.schedule

import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.ScheduledOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class AgendaAndTransitionsTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-03-10T12:00:00Z")
    private val day = LocalDate.of(2026, 3, 10)

    @Test
    fun agenda_splitsItemsIntoCurrentUpcomingAndEarlier() {
        val missed = item(at = "2026-03-10T08:00:00Z", status = OccurrenceStatus.PLANNED)
        val due = item(at = "2026-03-10T11:30:00Z", status = OccurrenceStatus.PLANNED)
        val later = item(at = "2026-03-10T15:00:00Z", status = OccurrenceStatus.PLANNED)
        val done = item(at = "2026-03-10T09:00:00Z", status = OccurrenceStatus.COMPLETED)

        val agenda = AgendaBuilder.build(listOf(missed, due, later, done), now)

        assertEquals(listOf(due.occurrenceId), agenda.current.map { it.occurrenceId })
        assertEquals(listOf(later.occurrenceId), agenda.upcoming.map { it.occurrenceId })
        assertEquals(setOf(missed.occurrenceId, done.occurrenceId), agenda.earlier.map { it.occurrenceId }.toSet())
    }

    @Test
    fun agenda_currentWindowIsOneHourBack_notUnlimited() {
        val justInside = item(at = "2026-03-10T11:01:00Z", status = OccurrenceStatus.PLANNED)
        val outside = item(at = "2026-03-10T10:59:00Z", status = OccurrenceStatus.PLANNED)

        val agenda = AgendaBuilder.build(listOf(justInside, outside), now)

        assertEquals(listOf(justInside.occurrenceId), agenda.current.map { it.occurrenceId })
        assertEquals(listOf(outside.occurrenceId), agenda.earlier.map { it.occurrenceId })
    }

    @Test
    fun agenda_rescheduledItemsAreStillPendingAndMarkedMoved() {
        val moved =
            AgendaItem(
                occurrenceId = UUID.randomUUID(),
                title = "Item",
                kind = AgendaKind.TASK,
                scheduledAt = Instant.parse("2026-03-10T14:00:00Z"),
                originalScheduledAt = Instant.parse("2026-03-10T09:00:00Z"),
                status = OccurrenceStatus.RESCHEDULED,
            )

        val agenda = AgendaBuilder.build(listOf(moved), now)

        assertEquals(listOf(moved.occurrenceId), agenda.upcoming.map { it.occurrenceId })
        assertTrue(agenda.upcoming.single().isMoved)
    }

    @Test
    fun agenda_sortsEachSectionByTime() {
        val first = item(at = "2026-03-10T13:00:00Z", status = OccurrenceStatus.PLANNED)
        val second = item(at = "2026-03-10T16:00:00Z", status = OccurrenceStatus.PLANNED)

        val agenda = AgendaBuilder.build(listOf(second, first), now)

        assertEquals(listOf(first.occurrenceId, second.occurrenceId), agenda.upcoming.map { it.occurrenceId })
    }

    @Test
    fun completeAndSkip_onlyApplyToPendingOccurrences() {
        val pending = occurrence(OccurrenceStatus.PLANNED)
        val completed = OccurrenceTransitions.complete(pending)!!
        assertEquals(OccurrenceStatus.COMPLETED, completed.status)

        assertNull(OccurrenceTransitions.skip(completed))
        assertNull(OccurrenceTransitions.complete(completed))
        assertEquals(OccurrenceStatus.SKIPPED, OccurrenceTransitions.skip(pending)!!.status)
    }

    @Test
    fun deletedOccurrence_cannotBeChanged() {
        val deleted = occurrence(OccurrenceStatus.PLANNED).copy(deletedAt = now)

        assertNull(OccurrenceTransitions.complete(deleted))
        assertNull(OccurrenceTransitions.skip(deleted))
    }

    @Test
    fun reschedule_marksMovedAndKeepsTheOriginalSlot() {
        val original = Instant.parse("2026-03-10T09:00:00Z")
        val occurrence = occurrence(OccurrenceStatus.PLANNED, at = original)
        val newTime = Instant.parse("2026-03-10T15:00:00Z")

        val moved = OccurrenceTransitions.reschedule(occurrence, newTime, utc)!!

        assertEquals(OccurrenceStatus.RESCHEDULED, moved.status)
        assertEquals(newTime, moved.scheduledAt)
        assertEquals(original, moved.originalScheduledAt)
    }

    @Test
    fun reschedule_backToTheOriginalTime_returnsToPlanned() {
        val original = Instant.parse("2026-03-10T09:00:00Z")
        val moved =
            OccurrenceTransitions.reschedule(
                occurrence(OccurrenceStatus.PLANNED, at = original),
                Instant.parse("2026-03-10T15:00:00Z"),
                utc,
            )!!

        val restored = OccurrenceTransitions.reschedule(moved, original, utc)!!

        assertEquals(OccurrenceStatus.PLANNED, restored.status)
        assertEquals(original, restored.scheduledAt)
    }

    @Test
    fun reschedule_updatesTheLocalDateInTheGivenZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val moved =
            OccurrenceTransitions.reschedule(
                occurrence(OccurrenceStatus.PLANNED, at = Instant.parse("2026-03-10T09:00:00Z")),
                Instant.parse("2026-03-10T16:00:00Z"),
                tokyo,
            )!!

        assertEquals(LocalDate.of(2026, 3, 11), moved.localDate)
    }

    @Test
    fun reschedule_isRefusedForCompletedItems() {
        val done = occurrence(OccurrenceStatus.COMPLETED)

        assertNull(OccurrenceTransitions.reschedule(done, Instant.parse("2026-03-10T15:00:00Z"), utc))
    }

    private fun item(
        at: String,
        status: OccurrenceStatus,
    ): AgendaItem {
        val instant = Instant.parse(at)
        return AgendaItem(
            occurrenceId = UUID.randomUUID(),
            title = "Item",
            kind = AgendaKind.ROUTINE,
            scheduledAt = instant,
            originalScheduledAt = instant,
            status = status,
        )
    }

    private fun occurrence(
        status: OccurrenceStatus,
        at: Instant = Instant.parse("2026-03-10T09:00:00Z"),
    ) = ScheduledOccurrence(
        id = UUID.randomUUID(),
        scheduleId = null,
        routineId = UUID.randomUUID(),
        taskId = null,
        originalScheduledAt = at,
        scheduledAt = at,
        localDate = day,
        status = status,
        createdAt = now,
    )
}
