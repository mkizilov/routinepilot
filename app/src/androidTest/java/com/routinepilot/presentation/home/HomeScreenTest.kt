package com.routinepilot.presentation.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.routinepilot.domain.model.Routine
import com.routinepilot.presentation.theme.RoutinePilotTheme
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyState_isShownWhenNoRoutines() {
        setScreen(HomeUiState())

        composeTestRule.onNodeWithText("No routines yet").assertIsDisplayed()
    }

    @Test
    fun routineTitles_areShownWhenPresent() {
        setScreen(HomeUiState(listOf(routine("Morning walk"))))

        composeTestRule.onNodeWithText("Morning walk").assertIsDisplayed()
    }

    @Test
    fun deleteFromMenu_reportsTheRoutine() {
        val target = routine("Morning walk")
        val actions = RecordingHomeActions()
        setScreen(HomeUiState(listOf(target)), actions)

        composeTestRule.onNodeWithContentDescription("More actions").performClick()
        composeTestRule.onNodeWithText("Delete").performClick()

        assertEquals(listOf("delete:${target.id}"), actions.calls)
    }

    @Test
    fun duplicateFromMenu_reportsTheRoutine() {
        val target = routine("Morning walk")
        val actions = RecordingHomeActions()
        setScreen(HomeUiState(listOf(target)), actions)

        composeTestRule.onNodeWithContentDescription("More actions").performClick()
        composeTestRule.onNodeWithText("Duplicate").performClick()

        assertEquals(listOf("duplicate:${target.id}"), actions.calls)
    }

    private fun routine(title: String) = Routine(UUID.randomUUID(), title, 0, Instant.EPOCH)

    private fun setScreen(state: HomeUiState, actions: HomeActions = RecordingHomeActions()) {
        composeTestRule.setContent {
            RoutinePilotTheme {
                HomeScreen(
                    uiState = state,
                    onOpenRoutine = {},
                    onNewRoutine = {},
                    actions = actions,
                    snackbarHostState = remember { SnackbarHostState() },
                )
            }
        }
    }

    private class RecordingHomeActions : HomeActions {
        val calls = mutableListOf<String>()

        override fun duplicateRoutine(id: UUID) {
            calls += "duplicate:$id"
        }

        override fun deleteRoutine(id: UUID) {
            calls += "delete:$id"
        }
    }
}
