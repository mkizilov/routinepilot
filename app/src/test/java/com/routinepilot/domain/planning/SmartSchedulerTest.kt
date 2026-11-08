package com.routinepilot.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SmartSchedulerTest {
    @Test
    fun placesTasksAroundCommitmentsAndBreaks_higherPriorityFirstWhenDeadlinesMatch() {
        val result =
            SmartScheduler.plan(
                request(
                    listOf(task("A", 60, priority = 3), task("B", 30, priority = 4)),
                    commitments = listOf(FixedCommitment("Dentist", TimeRange(at(10), at(11)))),
                    breaks = listOf(TimeRange(at(12), at(13))),
                ),
            )

        assertEquals(TimeRange(at(9), at(9, 30)), rangeOf(result, "B"))
        assertEquals(TimeRange(at(11), at(12)), rangeOf(result, "A"))
        assertTrue(result.unplaced.isEmpty())
        assertTrue(result.canApply)
    }

    @Test
    fun conflictingDeadlines_higherPriorityIsPlaced_andTheOtherIsExplained() {
        val result =
            SmartScheduler.plan(
                request(
                    listOf(
                        task("A", 60, priority = 5, deadline = at(10)),
                        task("B", 60, priority = 3, deadline = at(10)),
                    ),
                    workday = TimeRange(at(9), at(10)),
                ),
            )

        assertEquals(TimeRange(at(9), at(10)), rangeOf(result, "A"))
        val issue = issuesOf(result, "B").single() as PlanIssue.InsufficientTime
        assertEquals(60, issue.neededMinutes)
        assertEquals(0, issue.freeMinutes)
        assertEquals(listOf("A"), issue.blockers.map { it.label })
    }

    @Test
    fun insufficientTime_reportsNeededAndAvailableMinutes() {
        val result = SmartScheduler.plan(request(listOf(task("A", 120)), workday = TimeRange(at(9), at(10))))

        assertFalse(result.placements.any { it.taskId == id("A") })
        assertEquals(
            PlanIssue.InsufficientTime(
                neededMinutes = 120,
                freeMinutes = 60,
                largestFreeMinutes = 60,
                windowStart = at(9),
                windowEnd = at(10),
                blockers = emptyList(),
            ),
            issuesOf(result, "A").single(),
        )
    }

    @Test
    fun enoughTimeInTotal_butNoSingleStretch_isExplained() {
        val result =
            SmartScheduler.plan(
                request(
                    listOf(task("A", 100)),
                    workday = TimeRange(at(9), at(12)),
                    breaks = listOf(TimeRange(at(10), at(10, 30))),
                ),
            )

        val issue = issuesOf(result, "A").single() as PlanIssue.InsufficientTime
        assertEquals(150, issue.freeMinutes)
        assertEquals(90, issue.largestFreeMinutes)
        assertEquals(listOf("Break"), issue.blockers.map { it.label })
    }

    @Test
    fun prerequisiteRunsFirst_andItsDeadlineIsTightenedByTheDependent() {
        val result =
            SmartScheduler.plan(
                request(
                    listOf(
                        task("A", 30, priority = 5),
                        task("B", 30, priority = 1, deadline = at(10), after = setOf("A")),
                    ),
                    workday = TimeRange(at(9), at(18)),
                ),
            )

        assertEquals(TimeRange(at(9), at(9, 30)), rangeOf(result, "A"))
        assertEquals(TimeRange(at(9, 30), at(10)), rangeOf(result, "B"))
    }

    @Test
    fun unplacedPrerequisite_explainsWhyTheDependentCannotStart() {
        val result =
            SmartScheduler.plan(
                request(
                    listOf(task("A", 120), task("B", 30, after = setOf("A"))),
                    workday = TimeRange(at(9), at(10)),
                ),
            )

        assertTrue(issuesOf(result, "A").single() is PlanIssue.InsufficientTime)
        assertEquals(listOf(id("A")), (issuesOf(result, "B").single() as PlanIssue.DependencyUnplaced).prerequisites)
    }

    @Test
    fun cyclicDependency_isReportedForEveryMember_andOtherTasksStillPlace() {
        val result =
            SmartScheduler.plan(
                request(listOf(task("A", 30, after = setOf("B")), task("B", 30, after = setOf("A")), task("C", 30))),
            )

        val fromA = issuesOf(result, "A").single() as PlanIssue.CyclicDependency
        val fromB = issuesOf(result, "B").single() as PlanIssue.CyclicDependency
        assertEquals(setOf(id("A"), id("B")), fromA.members.toSet())
        assertEquals(setOf(id("A"), id("B")), fromB.members.toSet())
        assertTrue(result.placements.any { it.taskId == id("C") })
    }

    @Test
    fun pinnedTask_isNotMoved_andOtherTasksArrangeAroundIt() {
        val result =
            SmartScheduler.plan(
                request(listOf(task("A", 60), task("B", 60)), pins = mapOf(id("A") to at(10))),
            )

        assertEquals(Placement(id("A"), TimeRange(at(10), at(11)), pinned = true), placementOf(result, "A"))
        assertEquals(TimeRange(at(9), at(10)), rangeOf(result, "B"))
    }

    @Test
    fun pinnedTask_staysPut_whenAHigherPriorityTaskArrives() {
        val result =
            SmartScheduler.plan(
                request(
                    listOf(task("A", 60, priority = 1), task("H", 60, priority = 5)),
                    pins = mapOf(id("A") to at(10)),
                ),
            )

        assertEquals(TimeRange(at(10), at(11)), rangeOf(result, "A"))
        assertEquals(TimeRange(at(9), at(10)), rangeOf(result, "H"))
    }

    @Test
    fun pinOverlappingACommitment_isAConflict_andThePlanCannotBeApplied() {
        val result =
            SmartScheduler.plan(
                request(
                    listOf(task("A", 60)),
                    commitments = listOf(FixedCommitment("Dentist", TimeRange(at(10), at(11)))),
                    pins = mapOf(id("A") to at(10, 30)),
                ),
            )

        val conflict = result.overrideConflicts.single()
        assertEquals(id("A"), conflict.taskId)
        val overlap = conflict.issues.single() as PlanIssue.OverlapsBlocker
        assertEquals(listOf("Dentist"), overlap.blockers.map { it.label })
        assertFalse(result.canApply)
    }

    @Test
    fun pinPastDeadline_isAConflict() {
        val result =
            SmartScheduler.plan(
                request(listOf(task("A", 60, deadline = at(10))), pins = mapOf(id("A") to at(9, 30))),
            )

        assertEquals(
            PlanIssue.PastDeadline(at(10)),
            result.overrideConflicts
                .single()
                .issues
                .single(),
        )
        assertFalse(result.canApply)
    }

    @Test
    fun pinStartingBeforeAPinnedPrerequisite_isAConflict() {
        val result =
            SmartScheduler.plan(
                request(
                    listOf(task("A", 60), task("B", 60, after = setOf("A"))),
                    pins = mapOf(id("A") to at(10), id("B") to at(9)),
                ),
            )

        val conflict = result.overrideConflicts.single { it.taskId == id("B") }
        assertEquals(listOf(id("A")), (conflict.issues.single() as PlanIssue.PrerequisiteAfterStart).prerequisites)
    }

    @Test
    fun misalignedPin_andPinOutsideWorkday_areConflicts() {
        val result =
            SmartScheduler.plan(
                request(
                    listOf(task("A", 60), task("B", 60)),
                    pins = mapOf(id("A") to at(9) + 1, id("B") to at(17, 30)),
                ),
            )

        val misaligned = result.overrideConflicts.single { it.taskId == id("A") }
        assertEquals(PlanIssue.MisalignedStart, misaligned.issues.single())
        assertEquals(
            PlanIssue.OutsideWorkday,
            result.overrideConflicts
                .single { it.taskId == id("B") }
                .issues
                .single(),
        )
    }

    @Test
    fun overrideForAMissingTask_isReported_andBlocksApply() {
        val missing = id("gone")
        val result = SmartScheduler.plan(request(listOf(task("A", 30)), pins = mapOf(missing to at(9))))

        assertEquals(listOf(missing), result.staleOverrides)
        assertFalse(result.canApply)
    }

    @Test
    fun stableRescheduling_freedTimeDoesNotMoveAStillValidRecommendation() {
        val tasks = listOf(task("A", 60))
        val first =
            SmartScheduler.plan(
                request(tasks, commitments = listOf(FixedCommitment("Meeting", TimeRange(at(9), at(9, 30))))),
            )
        val remembered = PlanAdjustments.remember(request(tasks), first)

        val withoutHistory = SmartScheduler.plan(request(tasks))
        val withHistory = SmartScheduler.plan(remembered)

        assertEquals(TimeRange(at(9, 30), at(10, 30)), rangeOf(first, "A"))
        assertEquals(TimeRange(at(9, 30), at(10, 30)), rangeOf(withHistory, "A"))
        assertEquals(TimeRange(at(9), at(10)), rangeOf(withoutHistory, "A"))
    }

    @Test
    fun addingATaskWithALaterDeadline_doesNotMoveEarlierRecommendations() {
        val before = SmartScheduler.plan(request(listOf(task("A", 60, deadline = at(10)))))
        val after =
            SmartScheduler.plan(
                request(listOf(task("A", 60, deadline = at(10)), task("C", 60, deadline = at(17)))),
            )

        assertEquals(rangeOf(before, "A"), rangeOf(after, "A"))
        assertTrue(after.placements.any { it.taskId == id("C") })
    }

    @Test
    fun result_doesNotDependOnTheOrderTheTasksAreListed() {
        val tasks =
            listOf(
                task("A", 45, priority = 3),
                task("B", 30, priority = 4, after = setOf("A")),
                task("C", 60, priority = 4, deadline = at(12)),
                task("D", 30, priority = 2),
            )
        val commitments = listOf(FixedCommitment("Gym", TimeRange(at(15), at(16))))
        val breaks = listOf(TimeRange(at(11, 30), at(12)))

        val forward = SmartScheduler.plan(request(tasks, commitments = commitments, breaks = breaks))
        val backward = SmartScheduler.plan(request(tasks.reversed(), commitments = commitments, breaks = breaks))
        val again = SmartScheduler.plan(request(tasks, commitments = commitments, breaks = breaks))

        assertEquals(forward, backward)
        assertEquals(forward, again)
    }

    @Test
    fun durationRoundsUpToTheTimeGrid() {
        val result = SmartScheduler.plan(request(listOf(task("A", 50))))

        assertEquals(TimeRange(at(9), at(10)), rangeOf(result, "A"))
    }

    @Test
    fun deadlineBeforeWorkStarts_isReportedAsAClosedWindow() {
        val result = SmartScheduler.plan(request(listOf(task("A", 30, deadline = at(8)))))

        assertEquals(
            PlanIssue.WindowClosed(earliestStart = at(9), latestFinish = at(8)),
            issuesOf(result, "A").single(),
        )
    }

    @Test
    fun nudge_movesARecommendation_pinsIt_andUnpinReturnsItToAutomaticPlacement() {
        val tasks = listOf(task("A", 60))
        val base = request(tasks)
        val current = SmartScheduler.plan(base)

        val nudged = PlanAdjustments.nudge(base, current, id("A"), deltaMinutes = 30)!!
        val pinnedPlan = SmartScheduler.plan(nudged)
        val unpinnedPlan = SmartScheduler.plan(PlanAdjustments.unpin(nudged, id("A")))

        assertEquals(TimeRange(at(9, 30), at(10, 30)), rangeOf(pinnedPlan, "A"))
        assertTrue(placementOf(pinnedPlan, "A").pinned)
        assertEquals(TimeRange(at(9), at(10)), rangeOf(unpinnedPlan, "A"))
    }

    @Test
    fun remember_keepsAutomaticStarts_andLeavesPinsOut() {
        val tasks = listOf(task("A", 60), task("B", 30))
        val applied = SmartScheduler.plan(request(tasks, pins = mapOf(id("A") to at(11))))

        val remembered = PlanAdjustments.remember(request(tasks), applied)

        assertEquals(mapOf(id("B") to at(9)), remembered.previous)
    }

    @Test
    fun invalidInput_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { task("A", 0) }
        assertThrows(IllegalArgumentException::class.java) { task("A", 30, priority = 9) }
        assertThrows(IllegalArgumentException::class.java) { TimeRange(at(10), at(9)) }
        assertThrows(IllegalArgumentException::class.java) {
            request(listOf(task("A", 30), task("A", 30)))
        }
    }

    private fun id(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

    private fun at(
        hour: Int,
        minute: Int = 0,
    ): Int = hour * 60 + minute

    private fun task(
        name: String,
        minutes: Int,
        priority: Int = 3,
        deadline: Int? = null,
        after: Set<String> = emptySet(),
    ) = FlexibleTask(
        id = id(name),
        title = name,
        durationMinutes = minutes,
        priority = priority,
        deadlineMinute = deadline,
        dependsOn = after.map { id(it) }.toSet(),
    )

    private fun request(
        tasks: List<FlexibleTask>,
        workday: TimeRange = TimeRange(at(9), at(18)),
        breaks: List<TimeRange> = emptyList(),
        commitments: List<FixedCommitment> = emptyList(),
        pins: Map<UUID, Int> = emptyMap(),
    ) = PlanRequest(
        workday = workday,
        breaks = breaks,
        commitments = commitments,
        tasks = tasks,
        pins = pins,
    )

    private fun placementOf(
        result: PlanResult,
        name: String,
    ): Placement = result.placements.single { it.taskId == id(name) }

    private fun rangeOf(
        result: PlanResult,
        name: String,
    ): TimeRange = placementOf(result, name).range

    private fun issuesOf(
        result: PlanResult,
        name: String,
    ): List<PlanIssue> = result.unplaced.single { it.taskId == id(name) }.issues
}
