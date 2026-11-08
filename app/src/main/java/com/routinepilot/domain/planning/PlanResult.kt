package com.routinepilot.domain.planning

import java.util.UUID

data class Placement(
    val taskId: UUID,
    val range: TimeRange,
    val pinned: Boolean,
)

enum class BlockerKind { COMMITMENT, BREAK, PLACED, PINNED }

/** Something that occupies time in the window a task was trying to use. */
data class Blocker(
    val label: String,
    val kind: BlockerKind,
    val range: TimeRange,
)

/**
 * Why a task could not be placed, or why a manual override cannot be honored. Times are minutes after midnight.
 * Each reason
 * carries the facts a user needs to fix the problem.
 */
sealed interface PlanIssue {
    /** A pinned start is not on the time grid. */
    data object MisalignedStart : PlanIssue

    /** The task would start before the workday or finish after it. */
    data object OutsideWorkday : PlanIssue

    /** A pinned block overlaps a commitment, a break, or another pinned block. */
    data class OverlapsBlocker(
        val blockers: List<Blocker>,
    ) : PlanIssue

    /** A pinned block would finish after the task's deadline. */
    data class PastDeadline(
        val deadlineMinute: Int,
    ) : PlanIssue

    /** A pinned block starts before one of its prerequisites, which is also pinned, has finished. */
    data class PrerequisiteAfterStart(
        val prerequisites: List<UUID>,
    ) : PlanIssue

    /** The prerequisites finish after the latest time this task can finish, so no time is left. */
    data class WindowClosed(
        val earliestStart: Int,
        val latestFinish: Int,
    ) : PlanIssue

    /**
     * Not enough free time before the latest finish. [freeMinutes] is the free time in the
     * window, and [largestFreeMinutes]
     * is the longest single free stretch. Enough total time can still fail when it is split into pieces.
     */
    data class InsufficientTime(
        val neededMinutes: Int,
        val freeMinutes: Int,
        val largestFreeMinutes: Int,
        val windowStart: Int,
        val windowEnd: Int,
        val blockers: List<Blocker>,
    ) : PlanIssue

    /** A prerequisite could not be placed, so this task cannot start in order. */
    data class DependencyUnplaced(
        val prerequisites: List<UUID>,
    ) : PlanIssue

    /** These tasks depend on each other in a loop, so no order exists. */
    data class CyclicDependency(
        val members: List<UUID>,
    ) : PlanIssue
}

/** A task that could not be placed automatically, with every reason found. */
data class Unplaced(
    val taskId: UUID,
    val issues: List<PlanIssue>,
)

/** A manual override that cannot be honored. The task keeps the user's choice until the conflict is resolved. */
data class OverrideConflict(
    val taskId: UUID,
    val issues: List<PlanIssue>,
)

data class PlanResult(
    val placements: List<Placement>,
    val unplaced: List<Unplaced>,
    val overrideConflicts: List<OverrideConflict>,
    val staleOverrides: List<UUID>,
) {
    /** A plan can be applied only when every manual override is honored and none refers to a missing task. */
    val canApply: Boolean
        get() = overrideConflicts.isEmpty() && staleOverrides.isEmpty()
}
