package com.routinepilot.domain.execution

import com.routinepilot.testing.FakeMonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Clock starts at 100_000 ms. Expected timestamps below are derived from that origin. */
class RoutineTimerTest {
    private val clock = FakeMonotonicClock(100_000L)
    private val timer = RoutineTimer(clock)
    private val stretch = TimedStep(UUID.randomUUID(), "Stretch", 60)
    private val water = TimedStep(UUID.randomUUID(), "Water", 30)
    private val read = TimedStep(UUID.randomUUID(), "Read", 120)

    @Test
    fun load_showsThePlanWithoutStarting() {
        timer.load(listOf(stretch, water))

        val state = timer.state.value
        assertEquals(ExecutionStatus.NOT_STARTED, state.status)
        assertEquals(0, state.currentIndex)
        assertEquals(60_000L, state.currentRemainingMillis)
        assertEquals(90_000L, state.totalRemainingMillis)
    }

    @Test
    fun start_countsDownCurrentStepAndTotal() {
        timer.load(listOf(stretch, water))
        timer.start()

        timer.elapse(10_000)

        val state = timer.state.value
        assertEquals(ExecutionStatus.RUNNING, state.status)
        assertEquals(10_000L, state.currentElapsedMillis)
        assertEquals(50_000L, state.currentRemainingMillis)
        assertEquals(80_000L, state.totalRemainingMillis)
    }

    @Test
    fun pause_freezesCountdown_andResumeContinuesFromTheSamePoint() {
        timer.load(listOf(stretch, water))
        timer.start()
        timer.elapse(20_000)

        timer.pause()
        timer.elapse(30_000)
        assertEquals(ExecutionStatus.PAUSED, timer.state.value.status)
        assertEquals(40_000L, timer.state.value.currentRemainingMillis)

        timer.resume()
        timer.elapse(5_000)
        assertEquals(35_000L, timer.state.value.currentRemainingMillis)
    }

    @Test
    fun automaticAdvancement_completesTheStepAtItsExactExpiry() {
        timer.load(listOf(stretch, water))
        timer.start()

        assertEquals(emptyList<TimerEvent>(), timer.elapse(59_999))
        assertEquals(0, timer.state.value.currentIndex)

        val events = timer.elapse(1)

        assertEquals(listOf(TimerEvent.StepCompleted(stretch.id, 160_000L)), events)
        assertEquals(1, timer.state.value.currentIndex)
        assertEquals(0L, timer.state.value.currentElapsedMillis)
        assertEquals(30_000L, timer.state.value.currentRemainingMillis)
    }

    @Test
    fun overflow_carriesAcrossSeveralStepsWhenTicksArriveLate() {
        timer.load(listOf(stretch, water, read))
        timer.start()

        val events = timer.elapse(100_000)

        assertEquals(
            listOf(
                TimerEvent.StepCompleted(stretch.id, 160_000L),
                TimerEvent.StepCompleted(water.id, 190_000L),
            ),
            events,
        )
        val state = timer.state.value
        assertEquals(2, state.currentIndex)
        assertEquals(10_000L, state.currentElapsedMillis)
        assertEquals(110_000L, state.currentRemainingMillis)
        assertEquals(110_000L, state.totalRemainingMillis)
    }

    @Test
    fun expiryOfTheLastStep_completesTheRoutine() {
        timer.load(listOf(stretch))
        timer.start()

        val events = timer.elapse(60_000)

        assertEquals(
            listOf(TimerEvent.StepCompleted(stretch.id, 160_000L), TimerEvent.Finished(160_000L)),
            events,
        )
        val state = timer.state.value
        assertEquals(ExecutionStatus.COMPLETED, state.status)
        assertEquals(0L, state.totalRemainingMillis)
        assertEquals(null, state.currentRemainingMillis)
    }

    @Test
    fun pause_settlesAnExpiredStepBeforePausing() {
        timer.load(listOf(stretch, water))
        timer.start()
        clock.advance(65_000)

        val events = timer.pause()

        assertEquals(listOf(TimerEvent.StepCompleted(stretch.id, 160_000L)), events)
        val state = timer.state.value
        assertEquals(ExecutionStatus.PAUSED, state.status)
        assertEquals(1, state.currentIndex)
        assertEquals(5_000L, state.currentElapsedMillis)
    }

    @Test
    fun skip_movesOnAndRecordsTheSkip() {
        timer.load(listOf(stretch, water))
        timer.start()
        timer.elapse(5_000)

        val events = timer.skip()

        assertEquals(listOf(TimerEvent.StepSkipped(stretch.id, 105_000L)), events)
        assertEquals(1, timer.state.value.currentIndex)
        assertEquals(30_000L, timer.state.value.currentRemainingMillis)
    }

    @Test
    fun skip_onTheLastStep_completesTheRoutine() {
        timer.load(listOf(stretch))
        timer.start()

        val events = timer.skip()

        assertEquals(
            listOf(TimerEvent.StepSkipped(stretch.id, 100_000L), TimerEvent.Finished(100_000L)),
            events,
        )
        assertEquals(ExecutionStatus.COMPLETED, timer.state.value.status)
    }

    @Test
    fun previous_returnsToThePreviousStepAndRestartsIt_andRestartsTheFirstStep() {
        timer.load(listOf(stretch, water, read))
        timer.start()
        timer.elapse(10_000)
        timer.skip()
        timer.elapse(5_000)

        timer.previous()
        assertEquals(0, timer.state.value.currentIndex)
        assertEquals(0L, timer.state.value.currentElapsedMillis)
        assertEquals(60_000L, timer.state.value.currentRemainingMillis)

        timer.elapse(10_000)
        timer.previous()
        assertEquals(0, timer.state.value.currentIndex)
        assertEquals(60_000L, timer.state.value.currentRemainingMillis)
    }

    @Test
    fun finish_recordsTheCurrentStepAndCompletesTheRoutine() {
        timer.load(listOf(stretch, water))
        timer.start()
        timer.elapse(10_000)

        val events = timer.finish()

        assertEquals(
            listOf(TimerEvent.StepCompleted(stretch.id, 110_000L), TimerEvent.Finished(110_000L)),
            events,
        )
        assertEquals(ExecutionStatus.COMPLETED, timer.state.value.status)
    }

    @Test
    fun cancel_endsTheRoutineWithoutRecordingCompletion_andIgnoresLaterCommands() {
        timer.load(listOf(stretch, water))
        timer.start()
        timer.elapse(10_000)

        assertEquals(listOf(TimerEvent.Cancelled(110_000L)), timer.cancel())

        assertEquals(emptyList<TimerEvent>(), timer.start())
        assertEquals(emptyList<TimerEvent>(), timer.resume())
        assertEquals(emptyList<TimerEvent>(), timer.skip())
        assertEquals(emptyList<TimerEvent>(), timer.finish())
        assertEquals(ExecutionStatus.CANCELLED, timer.state.value.status)
    }

    @Test
    fun untimedStep_neverAutoAdvances_andIsExcludedFromTheTotal() {
        val untimed = TimedStep(UUID.randomUUID(), "Rest", null)
        timer.load(listOf(untimed, water))
        assertEquals(30_000L, timer.state.value.totalRemainingMillis)

        timer.start()
        val events = timer.elapse(600_000)

        assertEquals(emptyList<TimerEvent>(), events)
        val state = timer.state.value
        assertEquals(0, state.currentIndex)
        assertEquals(null, state.currentRemainingMillis)
        assertEquals(30_000L, state.totalRemainingMillis)

        timer.skip()
        assertEquals(30_000L, timer.state.value.currentRemainingMillis)
        assertEquals(30_000L, timer.state.value.totalRemainingMillis)
    }

    @Test
    fun restore_comesBackPaused_andCountsOnlyFromTheRecordedElapsedTime() {
        timer.restore(listOf(stretch, water), 1, 12_000L)

        assertEquals(ExecutionStatus.PAUSED, timer.state.value.status)
        assertEquals(18_000L, timer.state.value.currentRemainingMillis)

        timer.elapse(60_000)
        assertEquals(12_000L, timer.state.value.currentElapsedMillis)

        timer.resume()
        timer.elapse(3_000)
        assertEquals(15_000L, timer.state.value.currentElapsedMillis)
        assertEquals(15_000L, timer.state.value.currentRemainingMillis)
    }

    @Test
    fun restore_withElapsedBeyondTheStepDuration_advancesOnTheFirstTickAfterResume() {
        timer.restore(listOf(stretch, water), 0, 70_000L)
        timer.resume()

        val events = timer.tick()

        assertEquals(listOf(TimerEvent.StepCompleted(stretch.id, 90_000L)), events)
        assertEquals(1, timer.state.value.currentIndex)
        assertEquals(10_000L, timer.state.value.currentElapsedMillis)
    }

    @Test
    fun restore_withAnInvalidIndex_isIgnored() {
        timer.restore(listOf(stretch), 3, 0L)

        assertEquals(ExecutionStatus.NOT_STARTED, timer.state.value.status)
    }

    @Test
    fun start_withoutSteps_isIgnored() {
        timer.load(emptyList())

        assertEquals(emptyList<TimerEvent>(), timer.start())
        assertEquals(ExecutionStatus.NOT_STARTED, timer.state.value.status)
    }

    @Test
    fun snapshot_reflectsElapsedTimeWhileRunning() {
        timer.load(listOf(stretch, water))
        timer.start()
        timer.elapse(4_000)

        assertEquals(TimerSnapshot(ExecutionStatus.RUNNING, 0, 4_000L), timer.snapshot())
    }

    private fun RoutineTimer.elapse(millis: Long): List<TimerEvent> {
        clock.advance(millis)
        return tick()
    }
}
