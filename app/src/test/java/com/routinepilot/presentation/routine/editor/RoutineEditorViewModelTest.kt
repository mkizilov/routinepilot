package com.routinepilot.presentation.routine.editor

import androidx.lifecycle.SavedStateHandle
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.domain.validation.RoutineValidationError
import com.routinepilot.testing.FakeRoutineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineEditorViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val created = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun newEditor_startsEmptyWithoutShowingErrors() = runTest(dispatcher) {
        val viewModel = editorFor(FakeRoutineRepository())

        val state = viewModel.uiState.value
        assertTrue(state.isNewRoutine)
        assertFalse(state.isLoading)
        assertTrue(state.steps.isEmpty())
        assertFalse(state.showErrors)
    }

    @Test
    fun save_withBlankTitle_persistsNothingAndShowsErrors() = runTest(dispatcher) {
        val repository = FakeRoutineRepository()
        val viewModel = editorFor(repository)

        viewModel.onSave()

        val state = viewModel.uiState.value
        assertTrue(state.showErrors)
        assertEquals(RoutineValidationError.BLANK_TITLE, state.titleError)
        assertFalse(state.isClosed)
        assertTrue(repository.routines.value.isEmpty())
    }

    @Test
    fun save_persistsTrimmedRoutineWithOrderedStepsAndDurations() = runTest(dispatcher) {
        val repository = FakeRoutineRepository()
        val viewModel = editorFor(repository)
        viewModel.onTitleChange("  Morning  ")
        viewModel.onDescriptionChange("Start the day")
        viewModel.onAddStep()
        val stretch = viewModel.uiState.value.steps.last().id
        viewModel.onStepTitleChange(stretch, "Stretch")
        viewModel.onStepDurationChange(stretch, "10")
        viewModel.onAddStep()
        val water = viewModel.uiState.value.steps.last().id
        viewModel.onStepTitleChange(water, "Water")

        viewModel.onSave()

        val saved = repository.routines.value.single()
        assertEquals("Morning", saved.title)
        assertEquals("Start the day", saved.description)
        assertEquals(listOf("Stretch", "Water"), repository.steps.value.sortedBy { it.position }.map { it.title })
        assertEquals(600, repository.steps.value.single { it.title == "Stretch" }.durationSeconds)
        assertNull(repository.steps.value.single { it.title == "Water" }.durationSeconds)
        assertTrue(viewModel.uiState.value.isClosed)
    }

    @Test
    fun totalDuration_sumsStepMinutes() = runTest(dispatcher) {
        val viewModel = editorFor(FakeRoutineRepository())
        viewModel.onAddStep()
        viewModel.onAddStep()
        val (first, second) = viewModel.uiState.value.steps.map { it.id }
        viewModel.onStepDurationChange(first, "10")
        viewModel.onStepDurationChange(second, "25")

        assertEquals(35 * 60, viewModel.uiState.value.totalDurationSeconds)
    }

    @Test
    fun save_withInvalidDuration_isBlocked() = runTest(dispatcher) {
        val repository = FakeRoutineRepository()
        val viewModel = editorFor(repository)
        viewModel.onTitleChange("Evening")
        viewModel.onAddStep()
        val step = viewModel.uiState.value.steps.single().id
        viewModel.onStepTitleChange(step, "Read")
        viewModel.onStepDurationChange(step, "0")

        viewModel.onSave()

        assertEquals(RoutineValidationError.DURATION_INVALID, viewModel.uiState.value.steps.single().durationError)
        assertTrue(viewModel.uiState.value.showErrors)
        assertTrue(repository.routines.value.isEmpty())
    }

    @Test
    fun save_withBlankStepTitle_isBlocked() = runTest(dispatcher) {
        val repository = FakeRoutineRepository()
        val viewModel = editorFor(repository)
        viewModel.onTitleChange("Evening")
        viewModel.onAddStep()

        viewModel.onSave()

        assertTrue(repository.routines.value.isEmpty())
        assertEquals(RoutineValidationError.BLANK_TITLE, viewModel.uiState.value.steps.single().titleError)
    }

    @Test
    fun editExisting_loadsDraftAndKeepsIdentityOnRename() = runTest(dispatcher) {
        val routine = Routine(UUID.randomUUID(), "Old name", 3, created, description = "Notes")
        val step = RoutineStep(UUID.randomUUID(), routine.id, "Brush", 0, created, durationSeconds = 120)
        val repository = FakeRoutineRepository(listOf(routine), listOf(step))
        val viewModel = editorFor(repository, routine.id)

        assertEquals("Old name", viewModel.uiState.value.title)
        assertEquals("2", viewModel.uiState.value.steps.single().durationMinutes)

        viewModel.onTitleChange("New name")
        viewModel.onSave()

        val saved = repository.routines.value.single()
        assertEquals(routine.copy(title = "New name"), saved)
        assertEquals(listOf(step.id), repository.steps.value.map { it.id })
    }

    @Test
    fun removedStep_isPersistedOnlyOnSave_andCanBeUndone() = runTest(dispatcher) {
        val routine = Routine(UUID.randomUUID(), "Routine", 0, created)
        val steps = (0..2).map { RoutineStep(UUID.randomUUID(), routine.id, "Step $it", it, created) }
        val repository = FakeRoutineRepository(listOf(routine), steps)
        val viewModel = editorFor(repository, routine.id)

        viewModel.onRemoveStep(steps[1].id)

        assertEquals(1, viewModel.uiState.value.removedStep?.index)
        assertEquals(3, repository.steps.value.size)

        viewModel.onUndoRemoveStep()

        assertEquals(listOf("Step 0", "Step 1", "Step 2"), viewModel.uiState.value.steps.map { it.title })
        assertNull(viewModel.uiState.value.removedStep)
    }

    @Test
    fun removedStep_isDeletedFromStorageOnSave() = runTest(dispatcher) {
        val routine = Routine(UUID.randomUUID(), "Routine", 0, created)
        val steps = (0..1).map { RoutineStep(UUID.randomUUID(), routine.id, "Step $it", it, created) }
        val repository = FakeRoutineRepository(listOf(routine), steps)
        val viewModel = editorFor(repository, routine.id)

        viewModel.onRemoveStep(steps[0].id)
        viewModel.onRemovedStepShown()
        viewModel.onSave()

        assertEquals(listOf(steps[1].id), repository.steps.value.map { it.id })
        assertEquals(0, repository.steps.value.single().position)
    }

    @Test
    fun moveStep_reordersWithinBounds() = runTest(dispatcher) {
        val viewModel = editorFor(FakeRoutineRepository())
        repeat(3) { viewModel.onAddStep() }
        val (a, b, c) = viewModel.uiState.value.steps.map { it.id }
        viewModel.onStepTitleChange(a, "A")
        viewModel.onStepTitleChange(b, "B")
        viewModel.onStepTitleChange(c, "C")

        viewModel.onMoveStep(c, -1)
        assertEquals(listOf("A", "C", "B"), viewModel.uiState.value.steps.map { it.title })

        viewModel.onMoveStep(a, -1)
        assertEquals(listOf("A", "C", "B"), viewModel.uiState.value.steps.map { it.title })

        viewModel.onMoveStep(c, 1)
        assertEquals(listOf("A", "B", "C"), viewModel.uiState.value.steps.map { it.title })
    }

    @Test
    fun deleteRoutine_removesRoutineAndClosesEditor() = runTest(dispatcher) {
        val routine = Routine(UUID.randomUUID(), "Routine", 0, created)
        val repository = FakeRoutineRepository(listOf(routine))
        val viewModel = editorFor(repository, routine.id)

        viewModel.onDelete()

        assertTrue(repository.routines.value.isEmpty())
        assertTrue(viewModel.uiState.value.isClosed)
    }

    @Test
    fun missingRoutine_isReportedAndNotSaved() = runTest(dispatcher) {
        val repository = FakeRoutineRepository()
        val viewModel = editorFor(repository, UUID.randomUUID())

        viewModel.onTitleChange("Ghost")
        viewModel.onSave()

        assertTrue(viewModel.uiState.value.isMissing)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(repository.routines.value.isEmpty())
    }

    private fun editorFor(repository: FakeRoutineRepository, routineId: UUID? = null) =
        RoutineEditorViewModel(
            SavedStateHandle(routineId?.let { mapOf(ROUTINE_ID_ARG to it.toString()) } ?: emptyMap()),
            repository,
        )
}
