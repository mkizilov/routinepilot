package com.routinepilot.presentation.planning

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.routinepilot.domain.planning.OverrideConflict
import com.routinepilot.domain.planning.PlanIssue
import com.routinepilot.domain.planning.PlanResult
import com.routinepilot.domain.planning.TimeRange
import com.routinepilot.domain.repository.PlanningSettings
import com.routinepilot.presentation.theme.RoutinePilotTheme
import com.routinepilot.testing.WithFontScale
import com.routinepilot.testing.assertEveryClickableIsLabelled
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class PlanningScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loading_showsProgress_untilTheSettingsArrive() {
        setScreen(PlanningUiState(date = DATE, settings = null))

        composeTestRule.onNodeWithTag(TAG_PLANNING_LOADING).assertIsDisplayed()
    }

    @Test
    fun pinThatCannotBeHonored_explainsTheProblem_andBlocksApply() {
        val result =
            PlanResult(
                placements = emptyList(),
                unplaced = emptyList(),
                overrideConflicts = listOf(OverrideConflict(TASK, listOf(PlanIssue.MisalignedStart))),
                staleOverrides = emptyList(),
            )
        setScreen(PlanningUiState(date = DATE, settings = SETTINGS, result = result, pins = mapOf(TASK to 541)))

        composeTestRule.onNodeWithText("Pinned times that need a change").assertIsDisplayed()
        composeTestRule.onNodeWithText("The pinned start must be on a quarter hour.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Apply plan").assertIsNotEnabled()
    }

    @Test
    fun sectionHeadings_areMarkedForScreenReaders() {
        setScreen(PlanningUiState(date = DATE, settings = SETTINGS))

        composeTestRule
            .onNodeWithText("Recommended times")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun everyClickableControl_hasASpokenLabel() {
        setScreen(PlanningUiState(date = DATE, settings = SETTINGS))

        composeTestRule.assertEveryClickableIsLabelled()
    }

    @Test
    fun largeText_keepsTheExplanationAtTheTopOfThePlan() {
        setScreen(PlanningUiState(date = DATE, settings = SETTINGS), fontScale = 2f)

        composeTestRule.onNodeWithText(NOTE).assertIsDisplayed()
    }

    private fun setScreen(
        state: PlanningUiState,
        fontScale: Float = 1f,
    ) {
        composeTestRule.setContent {
            WithFontScale(fontScale) {
                RoutinePilotTheme {
                    PlanningScreen(
                        state = state,
                        onBack = {},
                        onSaveSettings = {},
                        onSavePlanned = {},
                        onRemovePlanned = {},
                        onNudge = { _, _ -> },
                        onTogglePin = {},
                        onApply = {},
                        onRefresh = {},
                    )
                }
            }
        }
    }

    private companion object {
        const val NOTE =
            "Suggestions only. Nothing changes until you apply the plan. " +
                "Fixed items, breaks, and your pins always win."
        val DATE: LocalDate = LocalDate.of(2026, 11, 8)
        val SETTINGS = PlanningSettings(TimeRange(540, 1080), emptyList())
        val TASK: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    }
}
