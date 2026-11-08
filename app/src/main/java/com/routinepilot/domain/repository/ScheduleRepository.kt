package com.routinepilot.domain.repository

import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduledOccurrence
import com.routinepilot.domain.schedule.AgendaItem
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

interface ScheduleRepository {
    fun observeSchedules(): Flow<List<Schedule>>

    fun observeSchedule(id: UUID): Flow<Schedule?>

    /** Items whose scheduled time falls in [from, to), with titles resolved. Items of deleted targets are omitted. */
    fun observeAgenda(
        from: Instant,
        to: Instant,
    ): Flow<List<AgendaItem>>

    fun observeOccurrences(routineId: UUID): Flow<List<ScheduledOccurrence>>

    /** Stores the rule and brings its pending occurrences in line with it, in one transaction. */
    suspend fun saveSchedule(
        schedule: Schedule,
        now: Instant,
    )

    /** Stops the schedule and removes its pending occurrences. Completed and skipped history is kept. */
    suspend fun deleteSchedule(
        id: UUID,
        now: Instant,
    )

    /**
     * Generates occurrences for every active schedule and re-anchors any schedule saved in another zone. Safe to
     * call repeatedly. Returns how many occurrences were created.
     */
    suspend fun ensureOccurrences(
        now: Instant,
        zone: ZoneId,
    ): Int

    suspend fun completeOccurrence(
        id: UUID,
        now: Instant,
    )

    suspend fun skipOccurrence(
        id: UUID,
        now: Instant,
    )

    suspend fun rescheduleOccurrence(
        id: UUID,
        newTime: Instant,
        zone: ZoneId,
        now: Instant,
    )

    suspend fun deleteOccurrence(
        id: UUID,
        now: Instant,
    )
}
