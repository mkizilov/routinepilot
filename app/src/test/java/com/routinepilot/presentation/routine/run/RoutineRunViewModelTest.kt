package com.routinepilot.presentation.routine.run

import androidx.lifecycle.SavedStateHandle
import com.routinepilot.data.execution.RoutineRunCoordinator
import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.presentation.routine.editor.ROUTINE_ID_ARG
import com.routinepilot.testing.FakeActiveExecutionStore
import com.routinepilot.testing.FakeExecutionRepository
import com.routinepilot.testing.FakeMonotonicClock
import com.routinepilot.testing.FakePausedRunReminders
import com.routinepilot.testing.FakeRoutineRepository
import com.routinepilot.testing.FakeRunForegroundLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The ViewModel only maps and forwards; run behaviour is covered by RoutineRunCoordinatorTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutineRunViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val wallNow: Instant = Instant.parse("2026-03-01T09:00:00Z")
    private val routine = Routine(UUID.randomUUID(), "Morning", 0, wallNow)
    private val stretch = RoutineStep(UUID.randomUUID(), routine.id, "Stretch", 0, wallNow, durationSeconds = 60)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun viewModel_showsTheOpenedRoutine_andStartsItThroughTheCoordinator() = runTest(dispatcher) {
        val viewModel = viewModelFor(routine.id, FakeActiveExecutionStore())

        assertEquals("Morning", viewModel.uiState.value.routineTitle)
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.start()

        assertEquals(ExecutionStatus.RUNNING, viewModel.uiState.value.timer.status)
    }

    @Test
    fun viewModel_whileAnotherRoutineRuns_hidesTheTitleAndTimer() = runTest(dispatcher) {
        val other = ActiveExecution(
            sessionId = UUID.randomUUID(),
            routineId = UUID.randomUUID(),
            startedAt = wallNow,
            stepIds = listOf(UUID.randomUUID()),
            stepIndex = 0,
            elapsedMillis = 0L,
            status = ExecutionStatus.PAUSED,
        )

        val state = viewModelFor(routine.id, FakeActiveExecutionStore(other)).uiState.value

        assertTrue(state.blockedByOtherRun)
        assertEquals("", state.routineTitle)
        assertTrue(state.timer.steps.isEmpty())
    }

    private fun TestScope.viewModelFor(routineId: UUID, store: FakeActiveExecutionStore): RoutineRunViewModel {
        val routines = FakeRoutineRepository(listOf(routine), listOf(stretch))
        val coordinator = RoutineRunCoordinator(
            routineRepository = routines,
            executionRepository = FakeExecutionRepository(),
            activeExecutionStore = store,
            monotonicClock = FakeMonotonicClock(),
            wallClock = Clock.fixed(wallNow, ZoneOffset.UTC),
            reminders = FakePausedRunReminders(),
            foreground = FakeRunForegroundLauncher(),
            scope = backgroundScope,
        )
        return RoutineRunViewModel(SavedStateHandle(mapOf(ROUTINE_ID_ARG to routineId.toString())), coordinator)
    }
}
