package com.routinepilot.presentation.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.routinepilot.domain.statistics.OccurrenceTotals
import com.routinepilot.domain.statistics.RoutineTotals
import com.routinepilot.domain.statistics.StatisticsCalculator
import com.routinepilot.domain.statistics.StatisticsInput
import com.routinepilot.presentation.theme.RoutinePilotTheme
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class StatsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.of(2026, 11, 4)

    @Test
    fun today_showsCompletionAndTheCurrentStreakFromRecordedHistory() {
        val input =
            StatisticsInput(
                occurrences =
                    mapOf(
                        today to OccurrenceTotals(completed = 3, skipped = 0, missed = 1),
                        today.minusDays(1) to OccurrenceTotals(completed = 1, skipped = 0, missed = 0),
                    ),
                sessions = emptyMap(),
                steps = emptyMap(),
                routines = emptyList(),
            )

        setScreen(StatsUiState(loaded = true, statistics = StatisticsCalculator.compute(input, today)))

        composeTestRule.onNodeWithTag(TAG_STATS_TODAY_PERCENT).assertTextEquals("75%")
        composeTestRule.onNodeWithTag(TAG_STATS_CURRENT_STREAK).assertTextEquals("2 days")
    }

    @Test
    fun nothingDueToday_saysSo_insteadOfShowingZeroPercent() {
        setScreen(
            StatsUiState(
                loaded = true,
                statistics = StatisticsCalculator.compute(StatisticsInput.EMPTY, today),
            ),
        )

        composeTestRule.onNodeWithTag(TAG_STATS_TODAY_PERCENT).assertTextEquals("Nothing due")
    }

    @Test
    fun consistency_showsEachRoutineByTitle() {
        val routineId = UUID.fromString("00000000-0000-4000-8000-0000000000cc")
        val input =
            StatisticsInput(
                occurrences = emptyMap(),
                sessions = emptyMap(),
                steps = emptyMap(),
                routines = listOf(RoutineTotals(routineId, completed = 3, missed = 1)),
            )

        setScreen(
            StatsUiState(
                loaded = true,
                statistics = StatisticsCalculator.compute(input, today),
                routineTitles = mapOf(routineId to "Evening wind-down"),
            ),
        )

        composeTestRule.onNodeWithText("Evening wind-down").assertIsDisplayed()
    }

    @Test
    fun beforeTheFirstLoad_showsAPlaceholder() {
        setScreen(StatsUiState())

        composeTestRule.onNodeWithText("Loading your progress…").assertIsDisplayed()
    }

    private fun setScreen(state: StatsUiState) {
        composeTestRule.setContent {
            RoutinePilotTheme {
                StatsScreen(state = state, onBack = {})
            }
        }
    }
}
