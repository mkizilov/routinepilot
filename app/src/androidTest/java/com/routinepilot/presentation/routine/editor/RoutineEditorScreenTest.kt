package com.routinepilot.presentation.routine.editor

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.routinepilot.domain.validation.RoutineValidationError
import com.routinepilot.presentation.theme.RoutinePilotTheme
import com.routinepilot.testing.WithFontScale
import com.routinepilot.testing.assertEveryClickableIsLabelled
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

class RoutineEditorScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun newRoutine_withoutSteps_showsEmptyStateMessage() {
        setScreen(RoutineEditorUiState())

        composeTestRule.onNodeWithText("No steps yet. Add the first step.").assertIsDisplayed()
    }

    @Test
    fun addStepButton_requestsNewStep() {
        val actions = RecordingEditorActions()
        setScreen(RoutineEditorUiState(), actions)

        composeTestRule.onNodeWithText("Add step").performClick()

        assertEquals(listOf("addStep"), actions.calls)
    }

    @Test
    fun saveAfterFailedValidation_showsErrorMessages() {
        setScreen(
            RoutineEditorUiState(
                showErrors = true,
                titleError = RoutineValidationError.BLANK_TITLE,
                steps =
                    listOf(
                        draft(
                            "Stretch",
                            durationMinutes = "0",
                            durationError = RoutineValidationError.DURATION_INVALID,
                        ),
                    ),
            ),
        )

        composeTestRule.onNodeWithText("Enter a name").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter whole minutes from 1 to 480, or leave blank").assertIsDisplayed()
    }

    @Test
    fun errorsStayHidden_untilSaveIsAttempted() {
        setScreen(RoutineEditorUiState(titleError = RoutineValidationError.BLANK_TITLE))

        composeTestRule.onNodeWithText("Enter a name").assertDoesNotExist()
    }

    @Test
    fun totalDuration_isShownAsHoursAndMinutes() {
        setScreen(
            RoutineEditorUiState(
                steps = listOf(draft("Stretch", durationMinutes = "10"), draft("Read", durationMinutes = "55")),
            ),
        )

        composeTestRule.onNodeWithText("Total: 1 h 5 min").assertIsDisplayed()
    }

    @Test
    fun saveButton_requestsSave() {
        val actions = RecordingEditorActions()
        setScreen(RoutineEditorUiState(title = "Morning"), actions)

        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(listOf("save"), actions.calls)
    }

    @Test
    fun saveButton_isDisabledWhileLoading() {
        setScreen(RoutineEditorUiState(isNewRoutine = false, isLoading = true))

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun moveButtons_areDisabledAtTheEdges_andRequestMoves() {
        val first = draft("First")
        val second = draft("Second")
        val actions = RecordingEditorActions()
        setScreen(RoutineEditorUiState(steps = listOf(first, second)), actions)

        composeTestRule.onNodeWithContentDescription("Move step 1 up").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Move step 1 down").performClick()

        assertEquals(listOf("move:${first.id}:1"), actions.calls)
    }

    @Test
    fun removeButton_requestsRemoval() {
        val step = draft("Stretch")
        val actions = RecordingEditorActions()
        setScreen(RoutineEditorUiState(steps = listOf(step)), actions)

        composeTestRule.onNodeWithContentDescription("Remove step 1").performClick()

        assertEquals(listOf("remove:${step.id}"), actions.calls)
    }

    @Test
    fun removedStep_offersUndoThatRequestsRestore() {
        val actions = RecordingEditorActions()
        setScreen(RoutineEditorUiState(removedStep = RemovedStep(draft("Water"), 1)), actions)

        composeTestRule.onNodeWithText("Removed Water").assertIsDisplayed()
        composeTestRule.onNodeWithText("Undo").performClick()

        assertEquals(listOf("undo"), actions.calls)
    }

    @Test
    fun deleteRoutine_requiresConfirmation() {
        val actions = RecordingEditorActions()
        setScreen(RoutineEditorUiState(isNewRoutine = false, title = "Morning"), actions)

        composeTestRule.onNodeWithText("Delete routine").performClick()
        composeTestRule.onNodeWithText("Delete routine?").assertIsDisplayed()
        assertEquals(emptyList<String>(), actions.calls)

        composeTestRule.onNodeWithText("Delete").performClick()

        assertEquals(listOf("delete"), actions.calls)
    }

    @Test
    fun deleteRoutine_cancelDoesNothing() {
        val actions = RecordingEditorActions()
        setScreen(RoutineEditorUiState(isNewRoutine = false, title = "Morning"), actions)

        composeTestRule.onNodeWithText("Delete routine").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun missingRoutine_showsMessageInsteadOfEditor() {
        setScreen(RoutineEditorUiState(isNewRoutine = false, isMissing = true))

        composeTestRule.onNodeWithText("This routine no longer exists.").assertIsDisplayed()
    }

    @Test
    fun stepControls_nameTheirStep_soEachButtonIsUnambiguous() {
        val first = draft("First")
        val second = draft("Second")
        val actions = RecordingEditorActions()
        setScreen(RoutineEditorUiState(steps = listOf(first, second)), actions)

        composeTestRule.onNodeWithContentDescription("Remove step 2").performClick()
        composeTestRule.onNodeWithContentDescription("Move step 2 up").performClick()

        assertEquals(listOf("remove:${second.id}", "move:${second.id}:-1"), actions.calls)
    }

    @Test
    fun loading_showsProgress_andHidesTheForm() {
        setScreen(RoutineEditorUiState(isNewRoutine = false, isLoading = true, title = "Morning"))

        composeTestRule.onNodeWithTag(TAG_LOADING).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_TITLE).assertDoesNotExist()
    }

    @Test
    fun stepsHeading_isMarkedAsAHeading_forScreenReaderNavigation() {
        setScreen(RoutineEditorUiState())

        composeTestRule
            .onNodeWithText("Steps")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun everyClickableControl_hasASpokenLabel() {
        setScreen(
            RoutineEditorUiState(
                isNewRoutine = false,
                title = "Morning",
                steps = listOf(draft("First"), draft("Second")),
            ),
        )

        composeTestRule.assertEveryClickableIsLabelled()
    }

    @Test
    fun largeText_keepsSaveVisibleAndTheTitleField() {
        setScreen(RoutineEditorUiState(title = "Morning", steps = listOf(draft("First"))), fontScale = 2f)

        composeTestRule.onNodeWithTag(TAG_SAVE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_TITLE).assertExists()
    }

    private fun draft(
        title: String,
        durationMinutes: String = "",
        durationError: RoutineValidationError? = null,
    ) = StepDraft(
        id = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
        title = title,
        durationMinutes = durationMinutes,
        durationError = durationError,
    )

    private fun setScreen(
        state: RoutineEditorUiState,
        actions: RoutineEditorActions = RecordingEditorActions(),
        fontScale: Float = 1f,
    ) {
        composeTestRule.setContent {
            WithFontScale(fontScale) {
                RoutinePilotTheme {
                    RoutineEditorScreen(
                        state = state,
                        actions = actions,
                        onBack = {},
                        snackbarHostState = remember { SnackbarHostState() },
                    )
                }
            }
        }
    }

    private class RecordingEditorActions : RoutineEditorActions {
        val calls = mutableListOf<String>()

        override fun onTitleChange(value: String) {}

        override fun onDescriptionChange(value: String) {}

        override fun onAddStep() {
            calls += "addStep"
        }

        override fun onStepTitleChange(
            stepId: UUID,
            value: String,
        ) {}

        override fun onStepDescriptionChange(
            stepId: UUID,
            value: String,
        ) {}

        override fun onStepDurationChange(
            stepId: UUID,
            value: String,
        ) {}

        override fun onMoveStep(
            stepId: UUID,
            offset: Int,
        ) {
            calls += "move:$stepId:$offset"
        }

        override fun onRemoveStep(stepId: UUID) {
            calls += "remove:$stepId"
        }

        override fun onUndoRemoveStep() {
            calls += "undo"
        }

        override fun onRemovedStepShown() {}

        override fun onSave() {
            calls += "save"
        }

        override fun onDelete() {
            calls += "delete"
        }
    }
}
