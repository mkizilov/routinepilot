package com.routinepilot.domain.planning

import java.util.UUID

/**
 * Places flexible tasks into the free time of one day. The result depends only on the request. It never depends on the
 * order the tasks were listed in, on the clock, or on chance, so the same request always gives the same plan.
 *
 * Rules, applied in this order:
 * 1. Commitments, breaks, and the working hours are unavailable.
 * 2. Manual pins are placed first and never move. A pin that overlaps something, falls outside working
 *    hours, misses its deadline, or starts before a pinned prerequisite finishes is reported as a conflict.
 *    It is not placed automatically.
 * 3. A task must finish before each dependent task starts. Its latest finish is tightened so that every
 *    dependent can still
 *    finish by its own latest finish. Tasks that depend on a failed task fail too.
 * 4. Remaining tasks are placed in this order: earliest latest finish, then higher priority, then longer
 *    duration, then id.
 * 5. A task keeps its previous start while that start is still free and valid. Otherwise it takes the
 *    earliest free slot.
 * 6. A task that cannot be placed reports every reason found, with the blockers in its window.
 *
 * Durations round up to the time grid. Completed tasks are not part of the request, so a dependency on
 * one is satisfied.
 */
object SmartScheduler {
    fun plan(request: PlanRequest): PlanResult = Planner(request).plan()
}

private const val BREAK_LABEL = "Break"

private class Planner(
    private val request: PlanRequest,
) {
    private val grid = request.granularityMinutes
    private val workday = request.workday

    /** Sorted by id, so nothing depends on the order the caller listed the tasks in. */
    private val tasks = request.tasks.sortedBy { it.id.toString() }
    private val byId = tasks.associateBy { it.id }
    private val slot = tasks.associate { it.id to alignUp(it.durationMinutes, grid) }
    private val prerequisites: Map<UUID, List<UUID>> =
        tasks.associate { task ->
            task.id to task.dependsOn.filter { it in byId }.sortedBy { it.toString() }
        }
    private val dependents: Map<UUID, List<UUID>> = buildDependents()
    private val cyclic: Set<UUID> = tasks.map { it.id }.filter { reaches(it, it) }.toSet()
    private val commitmentBlockers: List<Blocker> =
        request.commitments.map { Blocker(it.title, BlockerKind.COMMITMENT, it.range) } +
            request.breaks.map { Blocker(BREAK_LABEL, BlockerKind.BREAK, it) }
    private var free: List<TimeRange> = subtractAll(listOf(workday), commitmentBlockers.map { it.range })
    private val placed = linkedMapOf<UUID, TimeRange>()
    private val pinned = linkedSetOf<UUID>()
    private val failed = linkedSetOf<UUID>()
    private val overrideIssues = linkedMapOf<UUID, MutableList<PlanIssue>>()
    private val unplaced = mutableListOf<Unplaced>()
    private val latestFinishes = mutableMapOf<UUID, Int>()

    fun plan(): PlanResult {
        placePins()
        checkPinnedPrerequisites()
        scheduleAutomatically()
        reportCycles()
        return PlanResult(
            placements =
                placed
                    .map { (taskId, range) -> Placement(taskId, range, taskId in pinned) }
                    .sortedWith(compareBy<Placement>({ it.range.start }, { it.taskId.toString() })),
            unplaced = unplaced.toList(),
            overrideConflicts = overrideIssues.map { (taskId, issues) -> OverrideConflict(taskId, issues.toList()) },
            staleOverrides =
                request.pins.keys
                    .filter { it !in byId }
                    .sortedBy { it.toString() },
        )
    }

    private fun placePins() {
        val ordered =
            request.pins
                .filterKeys { it in byId }
                .entries
                .sortedWith(compareBy<Map.Entry<UUID, Int>>({ it.value }, { it.key.toString() }))
        for ((taskId, start) in ordered) {
            val issues = pinIssues(taskId, start)
            if (issues.isNotEmpty()) {
                overrideIssues.getOrPut(taskId) { mutableListOf() }.addAll(issues)
                failed += taskId
                continue
            }
            val range = TimeRange(start, start + slot.getValue(taskId))
            placed[taskId] = range
            pinned += taskId
            free = subtractAll(free, listOf(range))
        }
    }

    private fun pinIssues(
        taskId: UUID,
        start: Int,
    ): List<PlanIssue> {
        val end = start + slot.getValue(taskId)
        val issues = mutableListOf<PlanIssue>()
        if (start % grid != 0) issues += PlanIssue.MisalignedStart
        if (start < workday.start || end > workday.end) {
            issues += PlanIssue.OutsideWorkday
        } else {
            val range = TimeRange(start, end)
            val clashes = blockers().filter { it.range.overlaps(range) }
            if (clashes.isNotEmpty()) issues += PlanIssue.OverlapsBlocker(clashes)
        }
        val deadline = byId.getValue(taskId).deadlineMinute
        if (deadline != null && end > deadline) issues += PlanIssue.PastDeadline(deadline)
        return issues
    }

    private fun checkPinnedPrerequisites() {
        for (taskId in pinned.toList()) {
            val start = placed.getValue(taskId).start
            val late = prerequisites.getValue(taskId).filter { it in pinned && placed.getValue(it).end > start }
            if (late.isNotEmpty()) {
                overrideIssues.getOrPut(taskId) { mutableListOf() }.add(PlanIssue.PrerequisiteAfterStart(late))
            }
        }
    }

    private fun scheduleAutomatically() {
        val resolved = (pinned + failed).toMutableSet()
        val remaining = tasks.map { it.id }.filter { it !in resolved && it !in cyclic }.toMutableList()
        while (true) {
            val ready = remaining.filter { taskId -> prerequisites.getValue(taskId).all { it in resolved } }
            if (ready.isEmpty()) break
            val next = ready.minWith(placementOrder)
            remaining.remove(next)
            resolved += next
            attempt(next)
        }
        for (taskId in remaining) {
            val missing = prerequisites.getValue(taskId).filter { it !in placed }
            unplaced += Unplaced(taskId, listOf(PlanIssue.DependencyUnplaced(missing)))
        }
    }

    private fun attempt(taskId: UUID) {
        val need = slot.getValue(taskId)
        val failedPrerequisites = prerequisites.getValue(taskId).filter { it in failed }
        if (failedPrerequisites.isNotEmpty()) {
            fail(taskId, PlanIssue.DependencyUnplaced(failedPrerequisites))
            return
        }
        val earliest =
            maxOf(
                workday.start,
                prerequisites.getValue(taskId).maxOfOrNull { placed.getValue(it).end } ?: workday.start,
            )
        val latest = latestFinish(taskId)
        if (latest < earliest) {
            fail(taskId, PlanIssue.WindowClosed(earliest, latest))
            return
        }
        val start = keptStart(taskId, need, earliest, latest) ?: earliestFit(earliest, latest, need)
        if (start == null) {
            fail(taskId, insufficientTime(need, earliest, latest))
            return
        }
        val range = TimeRange(start, start + need)
        placed[taskId] = range
        free = subtractAll(free, listOf(range))
    }

    private fun keptStart(
        taskId: UUID,
        need: Int,
        earliest: Int,
        latest: Int,
    ): Int? {
        val previous = request.previous[taskId] ?: return null
        val inWindow = previous % grid == 0 && previous >= earliest && previous + need <= latest
        if (!inWindow) return null
        return previous.takeIf { start -> free.any { it.start <= start && start + need <= it.end } }
    }

    private fun earliestFit(
        earliest: Int,
        latest: Int,
        need: Int,
    ): Int? {
        for (gap in free) {
            val start = alignUp(maxOf(gap.start, earliest), grid)
            if (start + need <= minOf(gap.end, latest)) return start
        }
        return null
    }

    private fun insufficientTime(
        need: Int,
        earliest: Int,
        latest: Int,
    ): PlanIssue.InsufficientTime {
        val available = free.map { gap -> maxOf(0, minOf(gap.end, latest) - maxOf(gap.start, earliest)) }
        return PlanIssue.InsufficientTime(
            neededMinutes = need,
            freeMinutes = available.sum(),
            largestFreeMinutes = available.maxOrNull() ?: 0,
            windowStart = earliest,
            windowEnd = latest,
            blockers = blockers().filter { it.range.start < latest && earliest < it.range.end },
        )
    }

    /**
     * The latest minute this task may finish: its deadline, tightened by every dependent that still has to fit after
     * it.
     */
    private fun latestFinish(taskId: UUID): Int {
        latestFinishes[taskId]?.let { return it }
        var latest = byId.getValue(taskId).deadlineMinute ?: workday.end
        for (dependent in dependents.getValue(taskId)) {
            if (dependent in cyclic) continue
            val bound =
                if (dependent in pinned) {
                    placed.getValue(dependent).start
                } else {
                    latestFinish(dependent) - slot.getValue(dependent)
                }
            latest = minOf(latest, bound)
        }
        latestFinishes[taskId] = latest
        return latest
    }

    private fun reportCycles() {
        for (taskId in tasks.map { it.id }) {
            if (taskId !in cyclic) continue
            val members = cyclic.filter { reaches(taskId, it) && reaches(it, taskId) }.sortedBy { it.toString() }
            unplaced += Unplaced(taskId, listOf(PlanIssue.CyclicDependency(members)))
        }
    }

    private fun fail(
        taskId: UUID,
        issue: PlanIssue,
    ) {
        failed += taskId
        unplaced += Unplaced(taskId, listOf(issue))
    }

    private fun blockers(): List<Blocker> =
        commitmentBlockers +
            placed.map { (taskId, range) ->
                val kind = if (taskId in pinned) BlockerKind.PINNED else BlockerKind.PLACED
                Blocker(byId.getValue(taskId).title, kind, range)
            }

    private val placementOrder =
        Comparator<UUID> { a, b ->
            compareValuesBy(
                a,
                b,
                { latestFinish(it) },
                { -byId.getValue(it).priority },
                { -slot.getValue(it) },
                { it.toString() },
            )
        }

    private fun buildDependents(): Map<UUID, List<UUID>> {
        val result = tasks.associate { it.id to mutableListOf<UUID>() }
        for (task in tasks) {
            for (prerequisite in prerequisites.getValue(task.id)) result.getValue(prerequisite).add(task.id)
        }
        return result.mapValues { (_, list) -> list.sortedBy { it.toString() } }
    }

    /** Whether [from] needs [target] before it can start, directly or through other tasks. */
    private fun reaches(
        from: UUID,
        target: UUID,
    ): Boolean {
        val seen = mutableSetOf<UUID>()
        val stack = ArrayDeque(prerequisites.getValue(from))
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (next == target) return true
            if (seen.add(next)) stack.addAll(prerequisites.getValue(next))
        }
        return false
    }
}

private fun alignUp(
    minutes: Int,
    grid: Int,
): Int = (minutes + grid - 1) / grid * grid

private fun subtractAll(
    ranges: List<TimeRange>,
    cuts: List<TimeRange>,
): List<TimeRange> = cuts.fold(ranges) { remaining, cut -> remaining.flatMap { subtractOne(it, cut) } }

private fun subtractOne(
    range: TimeRange,
    cut: TimeRange,
): List<TimeRange> {
    if (!range.overlaps(cut)) return listOf(range)
    val parts = mutableListOf<TimeRange>()
    if (cut.start > range.start) parts += TimeRange(range.start, cut.start)
    if (cut.end < range.end) parts += TimeRange(cut.end, range.end)
    return parts
}
