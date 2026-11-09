package com.routinepilot.presentation.help

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.routinepilot.presentation.theme.RoutinePilotTheme
import com.routinepilot.testing.assertEveryClickableIsLabelled
import org.junit.Rule
import org.junit.Test

class HelpScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun firstTopic_isListed_withItsHeadingMarked() {
        setScreen()

        composeTestRule
            .onNodeWithText("Routines and steps")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeTestRule.onNodeWithText("Running a routine").assertIsDisplayed()
    }

    @Test
    fun everyClickableControl_hasASpokenLabel() {
        setScreen()

        composeTestRule.assertEveryClickableIsLabelled()
    }

    private fun setScreen() {
        composeTestRule.setContent {
            RoutinePilotTheme {
                HelpScreen(onBack = {})
            }
        }
    }
}
