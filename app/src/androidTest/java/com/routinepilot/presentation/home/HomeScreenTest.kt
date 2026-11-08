package com.routinepilot.presentation.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.routinepilot.domain.model.Routine
import com.routinepilot.presentation.theme.RoutinePilotTheme
import java.time.Instant
import java.util.UUID
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyState_isShownWhenNoRoutines() {
        composeTestRule.setContent {
            RoutinePilotTheme { HomeScreen(uiState = HomeUiState()) }
        }

        composeTestRule.onNodeWithText("No routines yet").assertIsDisplayed()
    }

    @Test
    fun routineTitles_areShownWhenPresent() {
        composeTestRule.setContent {
            val routine = Routine(UUID.randomUUID(), "Morning walk", 0, Instant.EPOCH)
            RoutinePilotTheme { HomeScreen(uiState = HomeUiState(listOf(routine))) }
        }

        composeTestRule.onNodeWithText("Morning walk").assertIsDisplayed()
    }
}
