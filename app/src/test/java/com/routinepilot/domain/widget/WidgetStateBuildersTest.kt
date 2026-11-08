package com.routinepilot.domain.widget

import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.execution.TimedStep
import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.schedule.AgendaItem
import com.routinepilot.domain.schedule.AgendaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Widget state is derived from the same data as the app. Times are fixed: now is 2026-11-04T12:00:00Z, which is
 * 13:00 in Europe/Berlin (CET, UTC+1), and the run checkpoint times are set relative to that moment.
 */
class WidgetStateBuildersTest {
    private val nowInstant: Instant = Instant.parse("2026-11-04T12:00:00Z")
    private val nowMillis = nowInstant.toEpochMilli()
    private val berlin = ZoneId.of("Europe/Berlin")

    // Today

    @Test
    fun today_listsDueNowFirstThenUpcomingInTime_andLeavesOutDoneSkippedAndEarlierItems() {
        val due = agenda("Stretch", "2026-11-04T11:30:00Z", OccurrenceStatus.PLANNED)
        val later = agenda("Read", "2026-11-04T15:00:00Z", OccurrenceStatus.PLANNED)
        val moved = AgendaItem(
            occurrenceId = UUID.randomUUID(),
            title = "Water",
            kind = AgendaKind.TASK,
            scheduledAt = Instant.parse("2026-11-04T16:30:00Z"),
            originalScheduledAt = Instant.parse("2026-11-04T14:00:00Z"),
            status = OccurrenceStatus.RESCHEDULED,
        )
        val last = agenda("Journal", "2026-11-04T18:00:00Z", OccurrenceStatus.PLANNED)
        val done = agenda("Brush", "2026-11-04T09:00:00Z", OccurrenceStatus.COMPLETED)
        val missed = agenda("Vitamins", "2026-11-04T08:00:00Z", OccurrenceStatus.PLANNED)

        val state = TodayWidgetStateBuilder.build(listOf(last, moved, done, missed, later, due), nowInstant, berlin)

        assertEquals(
            listOf("Stretch", "Read", "Water", "Journal"),
            state.items.map { it.title },
        )
        assertEquals(listOf("12:30", "16:00", "17:30", "19:00"), state.items.map { it.timeLabel })
        assertEquals(listOf(true, false, false, false), state.items.map { it.isDueNow })
        assertEquals(listOf(false, false, true, false), state.items.map { it.isMoved })
        assertEquals(4, state.totalCount)
    }

    @Test
    fun today_withNothingLeft_isEmpty() {
        val state = TodayWidgetStateBuilder.build(
            listOf(agenda("Brush", "2026-11-04T09:00:00Z", OccurrenceStatus.COMPLETED)),
            nowInstant,
            berlin,
        )

        assertTrue(state.items.isEmpty())
        assertEquals(0, state.totalCount)
    }

    @Test
    fun today_capsTheStoredItemsButKeepsTheTrueTotal() {
        val items = (1..10).map { agenda("Item $it", "2026-11-04T${12 + it}:00:00Z", OccurrenceStatus.PLANNED) }

        val state = TodayWidgetStateBuilder.build(items, nowInstant, berlin)

        assertEquals(TodayWidgetStateBuilder.MAX_ITEMS, state.items.size)
        assertEquals(10, state.totalCount)
    }

    @Test
    fun today_formatsTimesInTheDevicesZone() {
        val state = TodayWidgetStateBuilder.build(
            listOf(agenda("Stretch", "2026-11-04T19:00:00Z", OccurrenceStatus.PLANNED)),
            nowInstant,
            berlin,
        )

        assertEquals("20:00", state.items.single().timeLabel)
    }

    // Run

    @Test
    fun run_withNoActiveRun_isNoActiveRun() {
        assertEquals(RunWidgetState.NoActiveRun, RunWidgetStateBuilder.build(null, "Morning", plan(), nowMillis))
    }

    @Test
    fun run_whoseRoutineWasDeleted_isRoutineMissing() {
        val state = RunWidgetStateBuilder.build(run(ExecutionStatus.PAUSED), null, plan(), nowMillis)

        assertEquals(RunWidgetState.RoutineMissing, state)
    }

    @Test
    fun run_running_countsDownFromTheCheckpointUsingTheTimeSinceIt() {
        val state = RunWidgetStateBuilder.build(
            run(ExecutionStatus.RUNNING, elapsedMillis = 10_000L, checkpointAgeMillis = 2_000L),
            "Morning",
            plan(),
            nowMillis,
        ) as RunWidgetState.Active

        assertEquals(WidgetRunStatus.RUNNING, state.status)
        assertEquals(1, state.minutesLeftInStep)
        assertEquals(2, state.minutesLeftTotal)
        assertEquals("Stretch", state.stepTitle)
        assertEquals(1, state.stepNumber)
        assertEquals(2, state.stepCount)
        assertEquals("Water", state.nextStepTitle)
    }

    @Test
    fun run_paused_showsTheCheckpointTimeWithoutAdvancingIt() {
        val state = RunWidgetStateBuilder.build(
            run(ExecutionStatus.PAUSED, elapsedMillis = 10_000L, checkpointAgeMillis = 600_000L),
            "Morning",
            plan(),
            nowMillis,
        ) as RunWidgetState.Active

        assertEquals(WidgetRunStatus.PAUSED, state.status)
        assertEquals(1, state.minutesLeftInStep)
        assertEquals(2, state.minutesLeftTotal)
    }

    @Test
    fun run_runningWithAStaleCheckpoint_needsAttention() {
        val state = RunWidgetStateBuilder.build(
            run(ExecutionStatus.RUNNING, checkpointAgeMillis = 6_000L),
            "Morning",
            plan(),
            nowMillis,
        )

        assertEquals(RunWidgetState.NeedsAttention("Morning"), state)
    }

    @Test
    fun run_checkpointExactlyAtTheStaleLimit_isStillTrusted() {
        val state = RunWidgetStateBuilder.build(
            run(ExecutionStatus.RUNNING, checkpointAgeMillis = RunWidgetStateBuilder.STALE_CHECKPOINT_MILLIS),
            "Morning",
            plan(),
            nowMillis,
        )

        assertTrue(state is RunWidgetState.Active)
    }

    @Test
    fun run_runningFromBeforeCheckpointsHadTimestamps_needsAttention() {
        val state = RunWidgetStateBuilder.build(
            run(ExecutionStatus.RUNNING, savedAtEpochMillis = 0L),
            "Morning",
            plan(),
            nowMillis,
        )

        assertEquals(RunWidgetState.NeedsAttention("Morning"), state)
    }

    @Test
    fun run_whosePlanChanged_needsAttention() {
        val changed = run(ExecutionStatus.PAUSED).copy(stepIds = listOf(plan()[0].id, UUID.randomUUID()))

        assertEquals(
            RunWidgetState.NeedsAttention("Morning"),
            RunWidgetStateBuilder.build(changed, "Morning", plan(), nowMillis),
        )
    }

    @Test
    fun run_withAStepIndexOutsideItsPlan_needsAttention() {
        val out = run(ExecutionStatus.PAUSED, stepIndex = 5)

        assertEquals(
            RunWidgetState.NeedsAttention("Morning"),
            RunWidgetStateBuilder.build(out, "Morning", plan(), nowMillis),
        )
    }

    @Test
    fun run_withAStatusThatIsNotLive_needsAttention() {
        val finished = run(ExecutionStatus.COMPLETED)

        assertEquals(
            RunWidgetState.NeedsAttention("Morning"),
            RunWidgetStateBuilder.build(finished, "Morning", plan(), nowMillis),
        )
    }

    @Test
    fun run_onAnUntimedStep_hasNoStepCountdown_butTotalStillCountsTheLaterSteps() {
        val rest = TimedStep(UUID.randomUUID(), "Rest", null)
        val water = plan()[1]
        val untimed = run(ExecutionStatus.PAUSED, stepIds = listOf(rest.id, water.id))

        val state = RunWidgetStateBuilder.build(
            untimed,
            "Morning",
            listOf(rest, water),
            nowMillis,
        ) as RunWidgetState.Active

        assertNull(state.minutesLeftInStep)
        assertEquals(1, state.minutesLeftTotal)
    }

    @Test
    fun run_pastItsStepDuration_clampsToZeroAndRoundsUpTheTotal() {
        val state = RunWidgetStateBuilder.build(
            run(ExecutionStatus.RUNNING, elapsedMillis = 70_000L, checkpointAgeMillis = 0L),
            "Morning",
            plan(),
            nowMillis,
        ) as RunWidgetState.Active

        assertEquals(0, state.minutesLeftInStep)
        assertEquals(1, state.minutesLeftTotal)
    }

    @Test
    fun run_onItsLastStep_hasNoNextStep() {
        val state = RunWidgetStateBuilder.build(
            run(ExecutionStatus.PAUSED, stepIndex = 1),
            "Morning",
            plan(),
            nowMillis,
        ) as RunWidgetState.Active

        assertNull(state.nextStepTitle)
        assertEquals(2, state.stepNumber)
    }

    // Quick start

    @Test
    fun quickStart_listsRoutinesInSavedOrder() {
        val routines = listOf(
            Routine(UUID.randomUUID(), "C", 2, Instant.EPOCH),
            Routine(UUID.randomUUID(), "A", 0, Instant.EPOCH),
            Routine(UUID.randomUUID(), "B", 1, Instant.EPOCH),
        )

        val state = QuickStartStateBuilder.build(routines)

        assertEquals(listOf("A", "B", "C"), state.routines.map { it.title })
    }

    @Test
    fun quickStart_withNoRoutines_isEmpty() {
        assertTrue(QuickStartStateBuilder.build(emptyList()).routines.isEmpty())
    }

    // Helpers

    private fun agenda(title: String, at: String, status: OccurrenceStatus) = Instant.parse(at).let { instant ->
        AgendaItem(
            occurrenceId = UUID.randomUUID(),
            title = title,
            kind = AgendaKind.ROUTINE,
            scheduledAt = instant,
            originalScheduledAt = instant,
            status = status,
        )
    }

    private val routineId: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val sessionId: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a2")

    private fun plan(): List<TimedStep> = listOf(
        TimedStep(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), "Stretch", 60),
        TimedStep(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), "Water", 30),
    )

    private fun run(
        status: ExecutionStatus,
        elapsedMillis: Long = 0L,
        checkpointAgeMillis: Long = 0L,
        stepIndex: Int = 0,
        savedAtEpochMillis: Long = nowMillis - checkpointAgeMillis,
        stepIds: List<UUID> = plan().map { it.id },
    ) = ActiveExecution(
        sessionId = sessionId,
        routineId = routineId,
        startedAt = nowInstant,
        stepIds = stepIds,
        stepIndex = stepIndex,
        elapsedMillis = elapsedMillis,
        status = status,
        focusedMillis = elapsedMillis,
        savedAtEpochMillis = savedAtEpochMillis,
    )
}
