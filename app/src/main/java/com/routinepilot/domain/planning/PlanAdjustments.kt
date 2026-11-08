package com.routinepilot.domain.planning

import java.util.UUID

/**
 * Changes a plan request before it is applied. Each function returns a new request, so a preview can be
 * re-planned as often
 * as needed without touching the stored plan. A pin is the user's decision, and it stays in the request until the user
 * removes it.
 */
object PlanAdjustments {
    /**
     * Fixes a task at [startMinute]. The planner will not move it. It reports a conflict when it cannot be honored.
     */
    fun pin(request: PlanRequest, taskId: UUID, startMinute: Int): PlanRequest =
        request.copy(pins = request.pins + (taskId to startMinute))

    /** Returns a task to automatic placement. */
    fun unpin(request: PlanRequest, taskId: UUID): PlanRequest = request.copy(pins = request.pins - taskId)

    /**
     * Moves a recommended block by [deltaMinutes] and pins it there. Returns null when the task has no
     * recommended time in
     * [current].
     */
    fun nudge(request: PlanRequest, current: PlanResult, taskId: UUID, deltaMinutes: Int): PlanRequest? {
        val placement = current.placements.firstOrNull { it.taskId == taskId } ?: return null
        return pin(request, taskId, placement.range.start + deltaMinutes)
    }

    /**
     * Keeps the automatic placements of [applied] as the starts to prefer next time. Pinned blocks are not history,
     * because
     * the pin already keeps them.
     */
    fun remember(request: PlanRequest, applied: PlanResult): PlanRequest = request.copy(
        previous = applied.placements.filterNot { it.pinned }.associate { it.taskId to it.range.start },
    )
}
