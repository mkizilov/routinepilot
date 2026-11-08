package com.routinepilot.presentation.planning

import com.routinepilot.domain.model.Task
import com.routinepilot.domain.planning.FlexibleTask
import com.routinepilot.domain.planning.PlanRequest
import com.routinepilot.domain.planning.PlanResult
import com.routinepilot.domain.planning.TimeRange
import com.routinepilot.domain.repository.PlanningRepository
import com.routinepilot.domain.repository.PlanningSettings
import com.routinepilot.domain.repository.PlanningTaskSetting
import com.routinepilot.domain.repository.TaskRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-11-08T08:00:00Z"), ZoneOffset.UTC)
    private val taskId = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val task =
        Task(
            id = taskId,
            title = "Stretch",
            isCompleted = false,
            routineId = null,
            position = 0,
            createdAt = Instant.EPOCH,
        )
    private val setting =
        PlanningTaskSetting(
            taskId = taskId,
            durationMinutes = 60,
            priority = 3,
            deadlineMinute = null,
            dependsOn = emptySet(),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun apply_waitsForAReplanThatIsStillRunning_andRefusesAPinThatConflicts() =
        runTest(dispatcher) {
            val repository = FakePlanningRepository(setting)
            val viewModel = PlanningViewModel(repository, FakeTaskRepository(task), clock)
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.result!!
                    .canApply,
            )

            repository.gate = CompletableDeferred()
            viewModel.pin(taskId, 541)
            viewModel.apply()
            advanceUntilIdle()
            assertEquals(emptyList<PlanResult>(), repository.applied)

            repository.gate!!.complete(Unit)
            advanceUntilIdle()

            assertEquals(emptyList<PlanResult>(), repository.applied)
            assertEquals(PlanningMessage.CannotApply, viewModel.uiState.value.message)
        }

    @Test
    fun apply_writesTheCurrentPlan_whenNoPinConflicts() =
        runTest(dispatcher) {
            val repository = FakePlanningRepository(setting)
            val viewModel = PlanningViewModel(repository, FakeTaskRepository(task), clock)
            advanceUntilIdle()

            viewModel.apply()
            advanceUntilIdle()

            assertEquals(1, repository.applied.size)
            assertEquals(PlanningMessage.Applied, viewModel.uiState.value.message)
        }

    @Test
    fun aNewerReplan_isNotOverwrittenByAnOlderOne() =
        runTest(dispatcher) {
            val repository = FakePlanningRepository(setting)
            val viewModel = PlanningViewModel(repository, FakeTaskRepository(task), clock)
            advanceUntilIdle()

            val held = CompletableDeferred<Unit>()
            repository.gate = held
            viewModel.pin(taskId, 541)
            advanceUntilIdle()

            repository.gate = null
            viewModel.unpin(taskId)
            advanceUntilIdle()

            held.complete(Unit)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.result!!
                    .canApply,
            )
            assertTrue(
                viewModel.uiState.value.pins
                    .isEmpty(),
            )
        }

    private class FakePlanningRepository(
        private val setting: PlanningTaskSetting,
    ) : PlanningRepository {
        var gate: CompletableDeferred<Unit>? = null
        val applied = mutableListOf<PlanResult>()
        private val settingsFlow = MutableStateFlow(PlanningSettings(TimeRange(540, 1080), emptyList()))
        private val taskSettingsFlow = MutableStateFlow(listOf(setting))

        override fun observeSettings(): Flow<PlanningSettings> = settingsFlow

        override suspend fun saveSettings(settings: PlanningSettings) = Unit

        override fun observeTaskSettings(): Flow<List<PlanningTaskSetting>> = taskSettingsFlow

        override suspend fun saveTaskSetting(setting: PlanningTaskSetting) = Unit

        override suspend fun removeTaskSetting(taskId: UUID) = Unit

        override suspend fun buildRequest(
            date: LocalDate,
            zone: ZoneId,
            pins: Map<UUID, Int>,
        ): PlanRequest {
            gate?.await()
            val planned =
                FlexibleTask(
                    id = setting.taskId,
                    title = "Stretch",
                    durationMinutes = setting.durationMinutes,
                    priority = setting.priority,
                    deadlineMinute = setting.deadlineMinute,
                    dependsOn = setting.dependsOn,
                )
            return PlanRequest(
                workday = TimeRange(540, 1080),
                breaks = emptyList(),
                commitments = emptyList(),
                tasks = listOf(planned),
                pins = pins,
            )
        }

        override suspend fun storedPins(date: LocalDate): Map<UUID, Int> = emptyMap()

        override suspend fun applyPlan(
            date: LocalDate,
            result: PlanResult,
        ) {
            applied += result
        }
    }

    private class FakeTaskRepository(
        task: Task,
    ) : TaskRepository {
        private val tasksFlow = MutableStateFlow(listOf(task))

        override fun observeTasks(): Flow<List<Task>> = tasksFlow

        override suspend fun saveTask(task: Task) = Unit

        override suspend fun deleteTask(id: UUID) = Unit
    }
}
