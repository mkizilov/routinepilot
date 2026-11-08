package com.routinepilot.presentation.home

import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.repository.RoutineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        val viewModel = HomeViewModel(FakeRoutineRepository(listOf(Routine(1, "Morning walk"))))

        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(Routine(1, "Morning walk")), viewModel.uiState.value.routines)
    }

    private class FakeRoutineRepository(routines: List<Routine>) : RoutineRepository {
        private val routinesFlow = MutableStateFlow(routines)

        override fun observeRoutines(): Flow<List<Routine>> = routinesFlow
    }
}
