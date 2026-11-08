package com.routinepilot.presentation.stats

import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.statistics.OccurrenceTotals
import com.routinepilot.domain.statistics.RoutineTotals
import com.routinepilot.domain.statistics.StatisticsInput
import com.routinepilot.testing.FakeRoutineRepository
import com.routinepilot.testing.FakeStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate.of(2026, 11, 4)
    private val clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC)
    private val routineId = UUID.fromString("00000000-0000-4000-8000-0000000000aa")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_reflectsTheRecordedHistory() = runTest(dispatcher) {
        val repository = FakeStatsRepository(completedToday(3, 1))
        val viewModel = viewModel(repository, emptyList())

        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertEquals(75, state.statistics!!.today.completionPercent)
    }

    @Test
    fun uiState_updatesWhenHistoryChanges_withoutReloading() = runTest(dispatcher) {
        val repository = FakeStatsRepository(StatisticsInput.EMPTY)
        val viewModel = viewModel(repository, emptyList())
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertNull(viewModel.uiState.value.statistics!!.today.completionPercent)

        repository.input.value = completedToday(1, 0)
        runCurrent()

        assertEquals(100, viewModel.uiState.value.statistics!!.today.completionPercent)
        assertEquals(1, viewModel.uiState.value.statistics!!.currentStreak)
    }

    @Test
    fun uiState_resolvesRoutineTitlesForTheConsistencyList() = runTest(dispatcher) {
        val repository = FakeStatsRepository(
            StatisticsInput(
                occurrences = emptyMap(),
                sessions = emptyMap(),
                steps = emptyMap(),
                routines = listOf(RoutineTotals(routineId, completed = 2, missed = 2)),
            ),
        )
        val viewModel = viewModel(repository, listOf(Routine(routineId, "Morning", 0, Instant.EPOCH)))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("Morning", state.routineTitles[routineId])
        assertEquals(50, state.statistics!!.consistency.single().percent)
    }

    private fun completedToday(completed: Int, missed: Int) = StatisticsInput(
        occurrences = mapOf(today to OccurrenceTotals(completed = completed, skipped = 0, missed = missed)),
        sessions = emptyMap(),
        steps = emptyMap(),
        routines = emptyList(),
    )

    private fun viewModel(repository: FakeStatsRepository, routines: List<Routine>): StatsViewModel =
        StatsViewModel(repository, FakeRoutineRepository(routines), clock)
}
