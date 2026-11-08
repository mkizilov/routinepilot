package com.routinepilot.data.execution

import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.execution.RunEvent
import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.domain.model.SessionOutcome
import com.routinepilot.testing.FakeActiveExecutionStore
import com.routinepilot.testing.FakeExecutionRepository
import com.routinepilot.testing.FakeMonotonicClock
import com.routinepilot.testing.FakePausedRunReminders
import com.routinepilot.testing.FakeRoutineRepository
import com.routinepilot.testing.FakeRunForegroundLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val TICK_MILLIS = 250L

/**
 * Lifecycle and recovery behaviour of the process-wide run. Each "process" is a coroutine scope; cancelling it
 * simulates process death without any shutdown code running, while the store and clocks survive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutineRunCoordinatorTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val wallNow: Instant = Instant.parse("2026-03-01T09:00:00Z")
    private val wallClock: Clock = Clock.fixed(wallNow, ZoneOffset.UTC)
    private val clock = FakeMonotonicClock(100_000L)
    private val executions = FakeExecutionRepository()
    private val store = FakeActiveExecutionStore()
    private val reminders = FakePausedRunReminders()
    private val foreground = FakeRunForegroundLauncher()
    private val routine = Routine(UUID.randomUUID(), "Morning", 0, wallNow)
    private val stretch = step("Stretch", 60)
    private val water = step("Water", 30)
    private val routines = FakeRoutineRepository(listOf(routine), listOf(stretch, water))

    @Test
    fun open_loadsThePlanWithoutStartingOrRequestingForeground() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)

        coordinator.open(routine.id)

        val snapshot = coordinator.snapshot.value
        assertEquals("Morning", snapshot.routineTitle)
        assertFalse(snapshot.isLoading)
        assertEquals(ExecutionStatus.NOT_STARTED, snapshot.timer.status)
        assertEquals(listOf(stretch.id, water.id), snapshot.timer.steps.map { it.id })
        assertEquals(0, foreground.requests)
    }

    @Test
    fun start_recordsASession_persistsTheRun_andRequestsForeground() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)

        coordinator.open(routine.id)
        coordinator.start()

        assertEquals(ExecutionStatus.RUNNING, coordinator.snapshot.value.timer.status)
        val session = executions.sessions.value.single()
        assertEquals(wallNow, session.startedAt)
        assertNull(session.endedAt)
        assertEquals(listOf(stretch.id, water.id), store.current.value!!.stepIds)
        assertEquals(1, foreground.requests)
    }

    @Test
    fun backgroundRun_keepsTiming_andAdvancesWithNoScreenAttached() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        val events = collectEvents(coordinator)
        coordinator.open(routine.id)
        coordinator.start()

        coordinator.onBackground()
        advance(60_000)

        assertEquals(1, coordinator.snapshot.value.timer.currentIndex)
        assertEquals(listOf<RunEvent>(RunEvent.StepCompleted(routine.id, "Morning", "Stretch", "Water")), events)
        assertEquals(wallNow, executions.completions.value.single().completedAt)
    }

    @Test
    fun foreground_settlesStepsThatExpiredWhileTheTickerWasLate() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()

        clock.advance(70_000)
        coordinator.onForeground()

        val timer = coordinator.snapshot.value.timer
        assertEquals(1, timer.currentIndex)
        assertEquals(10_000L, timer.currentElapsedMillis)
        assertEquals(wallNow.minusSeconds(10), executions.completions.value.single().completedAt)
    }

    @Test
    fun background_writesACheckpointSoAProcessDeathLosesNothingSinceLeaving() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()
        advance(250)

        coordinator.onBackground()

        assertEquals(250L, store.current.value!!.elapsedMillis)
    }

    @Test
    fun whileRunning_theCheckpointIsNeverMoreThanOneSecondBehind() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()

        advance(10_500)

        val saved = store.current.value!!.elapsedMillis
        assertTrue("checkpoint was $saved ms", 10_500L - saved in 0L..1_000L)
    }

    @Test
    fun processDeath_restoresThePausedRunAtItsCheckpoint_andSchedulesAReminder() = runTest(dispatcher) {
        val processOne = newProcess()
        val first = newCoordinator(processOne)
        first.open(routine.id)
        first.start()
        advance(12_000)
        processOne.cancel()
        val saved = store.current.value!!

        val second = newCoordinator(backgroundScope)
        second.open(routine.id)

        val restored = second.snapshot.value.timer
        assertEquals(ExecutionStatus.PAUSED, restored.status)
        assertEquals(saved.elapsedMillis, restored.currentElapsedMillis)
        assertTrue(saved.elapsedMillis in 11_000L..12_000L)
        assertEquals(ExecutionStatus.PAUSED, store.current.value!!.status)
        assertEquals(saved.sessionId, reminders.scheduled.last())
    }

    @Test
    fun afterProcessDeath_resumeContinuesFromTheRestoredTime_notFromWhenTheProcessDied() = runTest(dispatcher) {
        val processOne = newProcess()
        val first = newCoordinator(processOne)
        first.open(routine.id)
        first.start()
        advance(12_000)
        processOne.cancel()

        val second = newCoordinator(backgroundScope)
        second.open(routine.id)
        val restoredElapsed = second.snapshot.value.timer.currentElapsedMillis
        clock.advance(600_000)
        second.resume()
        advance(5_000)

        assertEquals(restoredElapsed + 5_000L, second.snapshot.value.timer.currentElapsedMillis)
    }

    @Test
    fun notificationCommand_afterProcessDeath_adoptsTheStoredRunBeforeActing() = runTest(dispatcher) {
        val session = UUID.randomUUID()
        executions.saveSession(sessionRow(session, routine.id))
        store.save(
            ActiveExecution(
                sessionId = session,
                routineId = routine.id,
                startedAt = wallNow,
                stepIds = listOf(stretch.id, water.id),
                stepIndex = 0,
                elapsedMillis = 5_000L,
                status = ExecutionStatus.PAUSED,
            ),
        )
        val coordinator = newCoordinator(backgroundScope)
        val events = collectEvents(coordinator)

        coordinator.finish()

        assertEquals(listOf<RunEvent>(RunEvent.RoutineFinished(routine.id, "Morning")), events)
        assertNull(store.current.value)
        assertEquals(wallNow, executions.sessions.value.single().endedAt)
        assertEquals(stretch.id, executions.completions.value.single().stepId)
    }

    @Test
    fun pause_schedulesAReminder_andResumeCancelsIt() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()
        val session = executions.sessions.value.single().id

        coordinator.pause()
        assertEquals(listOf(session), reminders.scheduled)
        val cancelsBeforeResume = reminders.cancelCount

        coordinator.resume()

        assertEquals(cancelsBeforeResume + 1, reminders.cancelCount)
        assertEquals(ExecutionStatus.RUNNING, coordinator.snapshot.value.timer.status)
    }

    @Test
    fun finishAndCancel_cancelTheReminder_andEndTheSession() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()
        coordinator.pause()
        val cancelsBefore = reminders.cancelCount

        coordinator.cancel()

        assertEquals(cancelsBefore + 1, reminders.cancelCount)
        assertNull(store.current.value)
        assertEquals(wallNow, executions.sessions.value.single().endedAt)
    }

    @Test
    fun foreground_isRequestedOnStartAndResume_butNotOnPause() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()
        assertEquals(1, foreground.requests)

        coordinator.pause()
        assertEquals(1, foreground.requests)

        coordinator.resume()
        assertEquals(2, foreground.requests)
    }

    @Test
    fun foregroundRefusal_isReportedUntilTheRunIsRunningAgain() = runTest(dispatcher) {
        foreground.accepts = false
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()
        assertTrue(coordinator.snapshot.value.foregroundUnavailable)

        coordinator.pause()
        foreground.accepts = true
        coordinator.resume()

        assertFalse(coordinator.snapshot.value.foregroundUnavailable)
    }

    @Test
    fun finish_recordsTheCurrentStepSilently_andAnnouncesOnlyTheRoutineEnding() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        val events = collectEvents(coordinator)
        coordinator.open(routine.id)
        coordinator.start()
        advance(10_000)

        coordinator.finish()

        assertEquals(listOf<RunEvent>(RunEvent.RoutineFinished(routine.id, "Morning")), events)
        assertEquals(stretch.id, executions.completions.value.single().stepId)
    }

    @Test
    fun lastStepExpiry_announcesTheRoutineEnding() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        val events = collectEvents(coordinator)
        coordinator.open(routine.id)
        coordinator.start()

        advance(90_000)

        assertEquals(
            listOf<RunEvent>(
                RunEvent.StepCompleted(routine.id, "Morning", "Stretch", "Water"),
                RunEvent.RoutineFinished(routine.id, "Morning"),
            ),
            events,
        )
    }

    @Test
    fun cancel_closesTheRun_withoutAnnouncements() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        val events = collectEvents(coordinator)
        coordinator.open(routine.id)
        coordinator.start()
        advance(5_000)

        coordinator.cancel()

        assertTrue(coordinator.snapshot.value.isClosed)
        assertTrue(events.isEmpty())
        assertEquals(wallNow, executions.sessions.value.single().endedAt)
    }

    @Test
    fun reopeningTheRunningRoutine_keepsTheRunInsteadOfResettingIt() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()
        advance(5_000)

        coordinator.open(routine.id)

        val timer = coordinator.snapshot.value.timer
        assertEquals(ExecutionStatus.RUNNING, timer.status)
        assertEquals(5_000L, timer.currentElapsedMillis)
    }

    @Test
    fun openingAnotherRoutineWhileOneRuns_isBlocked_andLeavesTheRunUntouched() = runTest(dispatcher) {
        val other = Routine(UUID.randomUUID(), "Evening", 1, wallNow)
        routines.routines.value = listOf(routine, other)
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()
        advance(5_000)

        coordinator.open(other.id)

        val snapshot = coordinator.snapshot.value
        assertTrue(snapshot.blockedByOtherRun)
        assertEquals(ExecutionStatus.RUNNING, snapshot.timer.status)
        assertEquals(5_000L, snapshot.timer.currentElapsedMillis)
    }

    @Test
    fun storedRunOfAnotherRoutine_blocksOpeningThisOne() = runTest(dispatcher) {
        val otherRoutine = UUID.randomUUID()
        store.save(storedRun(routineId = otherRoutine, steps = listOf(stretch.id), status = ExecutionStatus.PAUSED))
        val coordinator = newCoordinator(backgroundScope)

        coordinator.open(routine.id)

        assertTrue(coordinator.snapshot.value.blockedByOtherRun)
        coordinator.start()
        assertTrue(executions.sessions.value.isEmpty())
    }

    @Test
    fun storedRunWhosePlanNoLongerMatches_isEndedRatherThanGuessedAt() = runTest(dispatcher) {
        val session = UUID.randomUUID()
        executions.saveSession(sessionRow(session, routine.id))
        store.save(storedRun(session, routine.id, listOf(stretch.id, UUID.randomUUID()), ExecutionStatus.PAUSED))
        val coordinator = newCoordinator(backgroundScope)

        coordinator.open(routine.id)

        assertEquals(ExecutionStatus.NOT_STARTED, coordinator.snapshot.value.timer.status)
        assertNull(store.current.value)
        assertEquals(session, executions.sessions.value.single().id)
    }

    @Test
    fun storedRunWithAnOutOfRangeStepIndex_isEndedInsteadOfBlockingNewRuns() = runTest(dispatcher) {
        val plan = listOf(stretch.id, water.id)
        store.save(storedRun(UUID.randomUUID(), routine.id, plan, ExecutionStatus.PAUSED, index = 5))
        val coordinator = newCoordinator(backgroundScope)

        coordinator.open(routine.id)

        assertEquals(ExecutionStatus.NOT_STARTED, coordinator.snapshot.value.timer.status)
        assertNull(store.current.value)
    }

    @Test
    fun missingRoutine_isReportedWithoutAnyRun() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)

        coordinator.open(UUID.randomUUID())

        val snapshot = coordinator.snapshot.value
        assertTrue(snapshot.isMissing)
        assertFalse(snapshot.isLoading)
    }

    private fun TestScope.newCoordinator(scope: CoroutineScope) = RoutineRunCoordinator(
        routineRepository = routines,
        executionRepository = executions,
        activeExecutionStore = store,
        monotonicClock = clock,
        wallClock = wallClock,
        reminders = reminders,
        foreground = foreground,
        scope = scope,
    )

    private fun TestScope.newProcess(): CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    private fun TestScope.collectEvents(coordinator: RoutineRunCoordinator): MutableList<RunEvent> {
        val events = mutableListOf<RunEvent>()
        backgroundScope.launch { coordinator.events.collect { events += it } }
        return events
    }

    private fun TestScope.advance(millis: Long) {
        repeat((millis / TICK_MILLIS).toInt()) {
            clock.advance(TICK_MILLIS)
            advanceTimeBy(TICK_MILLIS)
            runCurrent()
        }
    }

    @Test
    fun finish_recordsOutcomeFinished_andFocusedTimeExcludingPauses() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()
        advance(10_000)
        coordinator.pause()
        advance(30_000)
        coordinator.resume()
        advance(5_000)

        coordinator.finish()

        val session = executions.sessions.value.single()
        assertEquals(SessionOutcome.FINISHED, session.outcome)
        assertEquals(15_000L, session.focusedMillis)
    }

    @Test
    fun cancel_recordsOutcomeCancelled_andKeepsTheFocusedTimeAccrued() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()
        advance(8_000)

        coordinator.cancel()

        val session = executions.sessions.value.single()
        assertEquals(SessionOutcome.CANCELLED, session.outcome)
        assertEquals(8_000L, session.focusedMillis)
    }

    @Test
    fun reconcileStoredRun_endsARunWhoseRoutineWasDeleted() = runTest(dispatcher) {
        val session = UUID.randomUUID()
        executions.saveSession(sessionRow(session, routine.id))
        store.save(storedRun(session, routine.id, listOf(stretch.id), ExecutionStatus.PAUSED))
        val coordinator = RoutineRunCoordinator(
            routineRepository = FakeRoutineRepository(emptyList()),
            executionRepository = executions,
            activeExecutionStore = store,
            monotonicClock = clock,
            wallClock = wallClock,
            reminders = reminders,
            foreground = foreground,
            scope = backgroundScope,
        )

        coordinator.reconcileStoredRun()

        assertNull(store.current.value)
        assertEquals(SessionOutcome.CANCELLED, executions.sessions.value.single().outcome)
    }

    @Test
    fun reconcileStoredRun_leavesARunWhoseRoutineStillExists() = runTest(dispatcher) {
        val session = UUID.randomUUID()
        executions.saveSession(sessionRow(session, routine.id))
        store.save(storedRun(session, routine.id, listOf(stretch.id), ExecutionStatus.PAUSED))
        val coordinator = newCoordinator(backgroundScope)

        coordinator.reconcileStoredRun()

        assertEquals(session, store.current.value?.sessionId)
    }

    @Test
    fun start_checkpointsTheWallClockTime_soWidgetsCanTellALiveRunFromAStaleOne() = runTest(dispatcher) {
        val coordinator = newCoordinator(backgroundScope)
        coordinator.open(routine.id)
        coordinator.start()

        assertEquals(wallNow.toEpochMilli(), store.current.value!!.savedAtEpochMillis)
    }

    /** A run's session row exists from the moment it starts, so restored runs have one too. */
    private fun sessionRow(id: UUID, routineId: UUID) = ExecutionSession(
        id = id,
        routineId = routineId,
        occurrenceId = null,
        startedAt = wallNow.minusSeconds(60),
        endedAt = null,
        createdAt = wallNow.minusSeconds(60),
    )

    private fun storedRun(
        session: UUID = UUID.randomUUID(),
        routineId: UUID = routine.id,
        steps: List<UUID>,
        status: ExecutionStatus,
        index: Int = 0,
    ) = ActiveExecution(
        sessionId = session,
        routineId = routineId,
        startedAt = wallNow.minusSeconds(60),
        stepIds = steps,
        stepIndex = index,
        elapsedMillis = 5_000L,
        status = status,
    )

    private fun step(title: String, minutes: Int) = RoutineStep(
        id = UUID.randomUUID(),
        routineId = routine.id,
        title = title,
        position = if (title == "Stretch") 0 else 1,
        createdAt = wallNow,
        durationSeconds = minutes * 60,
    )
}
