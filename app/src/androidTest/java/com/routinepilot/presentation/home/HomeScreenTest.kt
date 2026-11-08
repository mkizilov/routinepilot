package com.routinepilot.presentation.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.routinepilot.domain.model.Routine
import com.routinepilot.presentation.theme.RoutinePilotTheme
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
            RoutinePilotTheme { HomeScreen(uiState = HomeUiState(listOf(Routine(1, "Morning walk")))) }
        }

        composeTestRule.onNodeWithText("Morning walk").assertIsDisplayed()
    }
}
