package com.routinepilot.domain.schedule

import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.ScheduledOccurrence
import java.time.Instant
import java.time.ZoneId

/**
 * Status changes for one occurrence. Each returns null when the change is not allowed, so callers can ignore
 * stale taps: finished or deleted occurrences cannot be changed.
 */
object OccurrenceTransitions {
    fun complete(occurrence: ScheduledOccurrence): ScheduledOccurrence? =
        occurrence.takeIf { canChange(it) }?.copy(status = OccurrenceStatus.COMPLETED)

    fun skip(occurrence: ScheduledOccurrence): ScheduledOccurrence? =
        occurrence.takeIf { canChange(it) }?.copy(status = OccurrenceStatus.SKIPPED)

    /**
     * Moves a pending occurrence to [newTime]. Moving it back to its original time returns it to PLANNED. The
     * original time is never changed, so the rule still cannot regenerate this slot.
     */
    fun reschedule(occurrence: ScheduledOccurrence, newTime: Instant, zone: ZoneId): ScheduledOccurrence? {
        if (!canChange(occurrence)) return null
        val status = if (newTime == occurrence.originalScheduledAt) {
            OccurrenceStatus.PLANNED
        } else {
            OccurrenceStatus.RESCHEDULED
        }
        return occurrence.copy(
            scheduledAt = newTime,
            localDate = ScheduleCalendar.dateOf(newTime, zone),
            status = status,
        )
    }

    private fun canChange(occurrence: ScheduledOccurrence): Boolean =
        occurrence.deletedAt == null && occurrence.status.isPending
}
