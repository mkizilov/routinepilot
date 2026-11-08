package com.routinepilot.domain.schedule

import com.routinepilot.domain.model.OccurrenceStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class AgendaKind { ROUTINE, TASK }

data class AgendaItem(
    val occurrenceId: UUID,
    val title: String,
    val kind: AgendaKind,
    val scheduledAt: Instant,
    val originalScheduledAt: Instant,
    val status: OccurrenceStatus,
) {
    val isMoved: Boolean
        get() = scheduledAt != originalScheduledAt
}

data class Agenda(
    val current: List<AgendaItem>,
    val upcoming: List<AgendaItem>,
    val earlier: List<AgendaItem>,
)

/**
 * Splits a day's items into sections as of [now].
 * - Current: pending items whose time has come within the last [CURRENT_WINDOW_MINUTES] minutes.
 * - Upcoming: pending items later than now.
 * - Earlier: everything else. Done and skipped items, and pending items older than the current window (missed).
 */
object AgendaBuilder {
    const val CURRENT_WINDOW_MINUTES = 60L

    fun build(items: List<AgendaItem>, now: Instant): Agenda {
        val windowStart = now.minus(Duration.ofMinutes(CURRENT_WINDOW_MINUTES))
        val sorted = items.sortedWith(compareBy({ it.scheduledAt }, { it.occurrenceId.toString() }))
        val pending = sorted.filter { it.status.isPending }
        val current = pending.filter { it.scheduledAt <= now && it.scheduledAt > windowStart }
        val upcoming = pending.filter { it.scheduledAt > now }
        val shown = (current + upcoming).toSet()
        return Agenda(current = current, upcoming = upcoming, earlier = sorted.filterNot { it in shown })
    }
}
