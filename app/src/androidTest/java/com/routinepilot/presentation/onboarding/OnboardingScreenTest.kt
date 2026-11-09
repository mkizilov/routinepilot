package com.routinepilot.presentation.onboarding

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.routinepilot.presentation.theme.RoutinePilotTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun next_walksThroughTheThreePages_andGetStartedFinishes() {
        var finished = false
        setScreen { finished = true }

        composeTestRule.onNodeWithText("Welcome to RoutinePilot").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNodeWithText("Routines and schedules").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNodeWithText("Your data, your choice").assertIsDisplayed()
        assertTrue(!finished)

        composeTestRule.onNodeWithText("Get started").performClick()

        assertTrue(finished)
    }

    @Test
    fun back_isOffOnTheFirstPage_andReturnsOnePageOtherwise() {
        setScreen {}

        composeTestRule.onNodeWithText("Back").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNodeWithText("Back").performClick()

        composeTestRule.onNodeWithText("Welcome to RoutinePilot").assertIsDisplayed()
    }

    @Test
    fun skip_finishesFromTheFirstPage() {
        var finished = false
        setScreen { finished = true }

        composeTestRule.onNodeWithText("Skip").performClick()

        assertTrue(finished)
    }

    @Test
    fun pageTitles_areMarkedAsHeadings() {
        setScreen {}

        composeTestRule
            .onNodeWithText("Welcome to RoutinePilot")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    private fun setScreen(onFinished: () -> Unit) {
        composeTestRule.setContent {
            RoutinePilotTheme {
                OnboardingScreen(onFinished = onFinished)
            }
        }
    }
}
