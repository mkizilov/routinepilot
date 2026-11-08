package com.routinepilot.domain.widget

import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.execution.TimedStep
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.schedule.AgendaBuilder
import com.routinepilot.domain.schedule.AgendaItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds widget state from data the app already holds. Builders are pure so each rule can be tested without Glance,
 * Room, or DataStore. The widgets read the same sources the app uses, so their state is never a second copy.
 */
object TodayWidgetStateBuilder {
    const val MAX_ITEMS = 8

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** Due-now items first, then upcoming. Done, skipped, and earlier items are left out, as on the agenda. */
    fun build(
        agenda: List<AgendaItem>,
        now: Instant,
        zone: ZoneId,
    ): TodayWidgetState {
        val split = AgendaBuilder.build(agenda, now)
        val all =
            split.current.map { it.toWidgetItem(zone, isDueNow = true) } +
                split.upcoming.map { it.toWidgetItem(zone, isDueNow = false) }
        return TodayWidgetState(items = all.take(MAX_ITEMS), totalCount = all.size)
    }

    private fun AgendaItem.toWidgetItem(
        zone: ZoneId,
        isDueNow: Boolean,
    ) = TodayWidgetItem(
        title = title,
        timeLabel = TIME.format(scheduledAt.atZone(zone)),
        isDueNow = isDueNow,
        isMoved = isMoved,
    )
}

object RunWidgetStateBuilder {
    /**
     * A RUNNING checkpoint older than this means the process that was timing has stopped. The app checkpoints about
     * once a second while it is running, so a live run is never this old.
     */
    const val STALE_CHECKPOINT_MILLIS = 5_000L

    private const val MILLIS_PER_MINUTE = 60_000L

    /**
     * Times are estimates for display. A running step is advanced by the time since its last checkpoint, so the
     * countdown is accurate to the second while the process is alive. The monotonic clock stays authoritative in the
     * app. The widget only uses wall-clock time for this estimate.
     */
    fun build(
        active: ActiveExecution?,
        routineTitle: String?,
        plan: List<TimedStep>,
        nowEpochMillis: Long,
    ): RunWidgetState {
        if (active == null) return RunWidgetState.NoActiveRun
        val title = routineTitle ?: return RunWidgetState.RoutineMissing
        val resolved = active.stepIds.mapNotNull { id -> plan.firstOrNull { it.id == id } }
        val planMatches =
            active.stepIds.isNotEmpty() &&
                resolved.size == active.stepIds.size &&
                active.stepIndex in resolved.indices
        val checkpointAge = nowEpochMillis - active.savedAtEpochMillis
        val status =
            when (active.status) {
                ExecutionStatus.RUNNING -> WidgetRunStatus.RUNNING
                ExecutionStatus.PAUSED -> WidgetRunStatus.PAUSED
                ExecutionStatus.NOT_STARTED, ExecutionStatus.COMPLETED, ExecutionStatus.CANCELLED -> null
            }
        val isStaleRunning = status == WidgetRunStatus.RUNNING && checkpointAge > STALE_CHECKPOINT_MILLIS
        if (!planMatches || status == null || isStaleRunning) return RunWidgetState.NeedsAttention(title)

        val sinceCheckpoint = if (status == WidgetRunStatus.RUNNING) checkpointAge.coerceAtLeast(0L) else 0L
        val elapsedNow = active.elapsedMillis + sinceCheckpoint
        val current = resolved[active.stepIndex]
        val stepRemaining = current.durationMillis?.let { (it - elapsedNow).coerceAtLeast(0L) }
        val laterRemaining = resolved.drop(active.stepIndex + 1).sumOf { it.durationMillis ?: 0L }
        return RunWidgetState.Active(
            routineId = active.routineId,
            sessionId = active.sessionId,
            routineTitle = title,
            status = status,
            stepTitle = current.title,
            stepNumber = active.stepIndex + 1,
            stepCount = resolved.size,
            minutesLeftInStep = stepRemaining?.let(::ceilMinutes),
            minutesLeftTotal = ceilMinutes((stepRemaining ?: 0L) + laterRemaining),
            nextStepTitle = resolved.getOrNull(active.stepIndex + 1)?.title,
        )
    }

    /** Rounds up, so a step with 20 seconds left reads as one minute rather than zero. */
    private fun ceilMinutes(millis: Long): Int = ((millis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()
}

object QuickStartStateBuilder {
    /** Routines in saved order. Deleted routines are not in [routines], because the repository hides them. */
    fun build(routines: List<Routine>): QuickStartState =
        QuickStartState(
            routines
                .sortedWith(compareBy({ it.position }, { it.id.toString() }))
                .map { QuickStartItem(it.id, it.title) },
        )
}
