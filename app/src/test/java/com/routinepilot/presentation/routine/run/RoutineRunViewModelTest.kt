package com.routinepilot.presentation.routine.run

import androidx.lifecycle.SavedStateHandle
import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.presentation.routine.editor.ROUTINE_ID_ARG
import com.routinepilot.testing.FakeActiveExecutionStore
import com.routinepilot.testing.FakeExecutionRepository
import com.routinepilot.testing.FakeMonotonicClock
import com.routinepilot.testing.FakeRoutineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val TICK_MILLIS = 250L

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineRunViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val wallNow: Instant = Instant.parse("2026-03-01T09:00:00Z")
    private val wallClock: Clock = Clock.fixed(wallNow, ZoneOffset.UTC)
    private val clock = FakeMonotonicClock(100_000L)
    private val executions = FakeExecutionRepository()
    private val routine = Routine(UUID.randomUUID(), "Morning", 0, wallNow)
    private val stretch = step("Stretch", 60)
    private val water = step("Water", 30)
    private val routines = FakeRoutineRepository(listOf(routine), listOf(stretch, water))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun load_showsTheRoutineAndItsPlanBeforeStarting() = runTest(dispatcher) {
        val viewModel = newViewModel()

        val state = viewModel.uiState.value
        assertEquals("Morning", state.routineTitle)
        assertFalse(state.isLoading)
        assertEquals(ExecutionStatus.NOT_STARTED, state.timer.status)
        assertEquals(listOf(stretch.id, water.id), state.timer.steps.map { it.id })
    }

    @Test
    fun start_recordsASessionAndPersistsTheRun() = runTest(dispatcher) {
        val store = FakeActiveExecutionStore()
        val viewModel = newViewModel(store = store)

        viewModel.start()

        assertEquals(ExecutionStatus.RUNNING, viewModel.uiState.value.timer.status)
        val session = executions.sessions.value.single()
        assertEquals(wallNow, session.startedAt)
        assertNull(session.endedAt)
        val saved = store.current.value!!
        assertEquals(session.id, saved.sessionId)
        assertEquals(listOf(stretch.id, water.id), saved.stepIds)
        assertEquals(0, saved.stepIndex)
        assertEquals(0L, saved.elapsedMillis)
        assertEquals(ExecutionStatus.RUNNING, saved.status)
    }

    @Test
    fun start_withoutSteps_recordsNothing() = runTest(dispatcher) {
        val emptyRoutine = Routine(UUID.randomUUID(), "Empty", 1, wallNow)
        val repository = FakeRoutineRepository(listOf(emptyRoutine))
        val viewModel = newViewModel(routineId = emptyRoutine.id, routines = repository)

        viewModel.start()

        assertEquals(ExecutionStatus.NOT_STARTED, viewModel.uiState.value.timer.status)
        assertTrue(executions.sessions.value.isEmpty())
    }

    @Test
    fun automaticAdvancement_recordsCompletionAtItsExpiryAndMovesOn() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.start()

        advance(60_000)

        assertEquals(1, viewModel.uiState.value.timer.currentIndex)
        val completion = executions.completions.value.single()
        assertEquals(stretch.id, completion.stepId)
        assertEquals(wallNow, completion.completedAt)
    }

    @Test
    fun pauseAndResume_keepOnlyTheTimeThatWasActuallyRunning() = runTest(dispatcher) {
        val store = FakeActiveExecutionStore()
        val viewModel = newViewModel(store = store)
        viewModel.start()
        advance(20_000)

        viewModel.pause()
        advance(30_000)

        assertEquals(ExecutionStatus.PAUSED, viewModel.uiState.value.timer.status)
        assertEquals(20_000L, viewModel.uiState.value.timer.currentElapsedMillis)
        assertEquals(20_000L, store.current.value!!.elapsedMillis)

        viewModel.resume()
        advance(10_000)

        assertEquals(30_000L, viewModel.uiState.value.timer.currentElapsedMillis)
    }

    @Test
    fun finish_recordsTheCurrentStep_closesTheSession_andClearsTheRun() = runTest(dispatcher) {
        val store = FakeActiveExecutionStore()
        val viewModel = newViewModel(store = store)
        viewModel.start()
        advance(10_000)

        viewModel.finish()

        assertEquals(ExecutionStatus.COMPLETED, viewModel.uiState.value.timer.status)
        assertEquals(stretch.id, executions.completions.value.single().stepId)
        assertEquals(wallNow, executions.sessions.value.single().endedAt)
        assertNull(store.current.value)
    }

    @Test
    fun cancel_endsTheSessionWithoutCompletion_andRequestsClose() = runTest(dispatcher) {
        val store = FakeActiveExecutionStore()
        val viewModel = newViewModel(store = store)
        viewModel.start()
        advance(10_000)

        viewModel.cancel()

        assertTrue(viewModel.uiState.value.isClosed)
        assertTrue(executions.completions.value.isEmpty())
        assertEquals(wallNow, executions.sessions.value.single().endedAt)
        assertNull(store.current.value)
    }

    @Test
    fun previousAndSkip_moveBetweenSteps() = runTest(dispatcher) {
        val store = FakeActiveExecutionStore()
        val viewModel = newViewModel(store = store)
        viewModel.start()

        viewModel.skip()
        assertEquals(1, viewModel.uiState.value.timer.currentIndex)
        assertEquals(1, store.current.value!!.stepIndex)

        viewModel.previous()
        assertEquals(0, viewModel.uiState.value.timer.currentIndex)
        assertTrue(executions.completions.value.isEmpty())
    }

    @Test
    fun restoredRun_comesBackPaused_andResumesFromItsSavedElapsedTime() = runTest(dispatcher) {
        val active = ActiveExecution(
            sessionId = UUID.randomUUID(),
            routineId = routine.id,
            startedAt = wallNow.minusSeconds(60),
            stepIds = listOf(stretch.id, water.id),
            stepIndex = 0,
            elapsedMillis = 15_000L,
            status = ExecutionStatus.RUNNING,
        )
        val viewModel = newViewModel(store = FakeActiveExecutionStore(active))

        val restored = viewModel.uiState.value.timer
        assertEquals(ExecutionStatus.PAUSED, restored.status)
        assertEquals(15_000L, restored.currentElapsedMillis)
        assertEquals(45_000L, restored.currentRemainingMillis)

        viewModel.resume()
        advance(45_000)

        assertEquals(1, viewModel.uiState.value.timer.currentIndex)
        assertEquals(stretch.id, executions.completions.value.single().stepId)
        assertEquals(wallNow, executions.completions.value.single().completedAt)
    }

    @Test
    fun restoredRun_whoseStepsChanged_isEndedInsteadOfGuessed() = runTest(dispatcher) {
        val active = ActiveExecution(
            sessionId = UUID.randomUUID(),
            routineId = routine.id,
            startedAt = wallNow.minusSeconds(60),
            stepIds = listOf(stretch.id, UUID.randomUUID()),
            stepIndex = 0,
            elapsedMillis = 5_000L,
            status = ExecutionStatus.PAUSED,
        )
        val store = FakeActiveExecutionStore(active)

        val viewModel = newViewModel(store = store)

        assertEquals(ExecutionStatus.NOT_STARTED, viewModel.uiState.value.timer.status)
        assertNull(store.current.value)
        val ended = executions.sessions.value.single()
        assertEquals(active.sessionId, ended.id)
        assertEquals(wallNow, ended.endedAt)
    }

    @Test
    fun start_isBlocked_whileAnotherRoutineIsInProgress() = runTest(dispatcher) {
        val other = ActiveExecution(
            sessionId = UUID.randomUUID(),
            routineId = UUID.randomUUID(),
            startedAt = wallNow,
            stepIds = listOf(UUID.randomUUID()),
            stepIndex = 0,
            elapsedMillis = 0L,
            status = ExecutionStatus.PAUSED,
        )
        val store = FakeActiveExecutionStore(other)
        val viewModel = newViewModel(store = store)

        viewModel.start()

        assertTrue(viewModel.uiState.value.blockedByOtherRun)
        assertEquals(ExecutionStatus.NOT_STARTED, viewModel.uiState.value.timer.status)
        assertTrue(executions.sessions.value.isEmpty())
        assertEquals(other, store.current.value)
    }

    @Test
    fun leavingTheForeground_pausesARunningTimer() = runTest(dispatcher) {
        val store = FakeActiveExecutionStore()
        val viewModel = newViewModel(store = store)
        viewModel.start()
        advance(5_000)

        viewModel.onStopped()

        assertEquals(ExecutionStatus.PAUSED, viewModel.uiState.value.timer.status)
        assertEquals(5_000L, store.current.value!!.elapsedMillis)
    }

    @Test
    fun runningTimer_savesProgressEveryFiveSeconds() = runTest(dispatcher) {
        val store = FakeActiveExecutionStore()
        val viewModel = newViewModel(store = store)
        viewModel.start()

        advance(4_750)
        assertEquals(0L, store.current.value!!.elapsedMillis)

        advance(250)
        assertEquals(5_000L, store.current.value!!.elapsedMillis)
    }

    @Test
    fun missingRoutine_isReportedWithoutStarting() = runTest(dispatcher) {
        val viewModel = newViewModel(routineId = UUID.randomUUID())

        assertTrue(viewModel.uiState.value.isMissing)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    private fun TestScope.advance(millis: Long) {
        repeat((millis / TICK_MILLIS).toInt()) {
            clock.advance(TICK_MILLIS)
            advanceTimeBy(TICK_MILLIS)
            runCurrent()
        }
    }

    private fun newViewModel(
        routineId: UUID = routine.id,
        routines: FakeRoutineRepository = this.routines,
        store: FakeActiveExecutionStore = FakeActiveExecutionStore(),
    ) = RoutineRunViewModel(
        savedStateHandle = SavedStateHandle(mapOf(ROUTINE_ID_ARG to routineId.toString())),
        routineRepository = routines,
        executionRepository = executions,
        activeExecutionStore = store,
        monotonicClock = clock,
        wallClock = wallClock,
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
