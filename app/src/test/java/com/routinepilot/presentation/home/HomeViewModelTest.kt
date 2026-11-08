package com.routinepilot.presentation.home

import com.routinepilot.domain.model.Routine
import com.routinepilot.testing.FakeRoutineRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_mirrorsRepositoryRoutines() = runTest(dispatcher) {
        val routine = Routine(UUID.randomUUID(), "Morning walk", 0, Instant.EPOCH)
        val viewModel = HomeViewModel(FakeRoutineRepository(listOf(routine)))

        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(routine), viewModel.uiState.value.routines)
    }

    @Test
    fun deleteRoutine_removesRoutineAndEmitsEventThatCanBeUndone() = runTest(dispatcher) {
        val routine = Routine(UUID.randomUUID(), "Morning walk", 0, Instant.EPOCH)
        val repository = FakeRoutineRepository(listOf(routine))
        val viewModel = HomeViewModel(repository)

        viewModel.deleteRoutine(routine.id)
        val event = viewModel.events.first()

        assertEquals(HomeEvent.RoutineDeleted(routine.id), event)
        assertTrue(repository.routines.value.isEmpty())

        viewModel.undoDelete(routine.id)
        advanceUntilIdle()

        assertEquals(listOf(routine), repository.routines.value)
    }

    @Test
    fun duplicateRoutine_addsCopyAndEmitsEvent() = runTest(dispatcher) {
        val routine = Routine(UUID.randomUUID(), "Morning walk", 0, Instant.EPOCH)
        val repository = FakeRoutineRepository(listOf(routine))
        val viewModel = HomeViewModel(repository)

        viewModel.duplicateRoutine(routine.id)

        assertEquals(HomeEvent.RoutineDuplicated, viewModel.events.first())
        assertEquals(
            listOf("Morning walk", "Morning walk (copy)"),
            repository.routines.value.sortedBy { it.position }.map { it.title },
        )
    }
}
