package com.routinepilot.presentation.routine.run

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.execution.TimedStep
import com.routinepilot.domain.execution.TimerState
import com.routinepilot.presentation.theme.RoutinePilotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class RoutineRunScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val stretch = TimedStep(UUID.randomUUID(), "Stretch", 60)
    private val water = TimedStep(UUID.randomUUID(), "Water", 30)

    @Test
    fun notStarted_showsCountdownAndTotal_andStartRunsTheRoutine() {
        val actions = RecordingRunActions()
        setScreen(
            runState(
                status = ExecutionStatus.NOT_STARTED,
                steps = listOf(stretch, water),
                remaining = 60_000L,
                total = 90_000L,
            ),
            actions,
        )

        composeTestRule.onNodeWithTag(TAG_RUN_COUNTDOWN).assertTextEquals("01:00")
        composeTestRule.onNodeWithTag(TAG_RUN_TOTAL).assertTextEquals("Total left: 01:30")
        composeTestRule.onNodeWithText("Start").performClick()

        assertEquals(listOf("start"), actions.calls)
    }

    @Test
    fun notStarted_withoutSteps_disablesStart() {
        setScreen(runState(status = ExecutionStatus.NOT_STARTED, steps = emptyList()))

        composeTestRule.onNodeWithText("Start").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Add steps to start this routine").assertIsDisplayed()
    }

    @Test
    fun running_showsCountdown_andPauseRequestsPause() {
        val actions = RecordingRunActions()
        setScreen(
            runState(status = ExecutionStatus.RUNNING, steps = listOf(stretch, water), remaining = 65_000L),
            actions,
        )

        composeTestRule.onNodeWithTag(TAG_RUN_COUNTDOWN).assertTextEquals("01:05")
        composeTestRule.onNodeWithText("Pause").performClick()

        assertEquals(listOf("pause"), actions.calls)
    }

    @Test
    fun paused_showsResume_andResumeRequestsResume() {
        val actions = RecordingRunActions()
        setScreen(runState(status = ExecutionStatus.PAUSED, steps = listOf(stretch, water)), actions)

        composeTestRule.onNodeWithText("Resume").performClick()

        assertEquals(listOf("resume"), actions.calls)
    }

    @Test
    fun stepControls_requestPreviousSkipAndFinish() {
        val actions = RecordingRunActions()
        setScreen(runState(status = ExecutionStatus.RUNNING, steps = listOf(stretch, water)), actions)

        composeTestRule.onNodeWithText("Previous").performClick()
        composeTestRule.onNodeWithText("Skip step").performClick()
        composeTestRule.onNodeWithText("Finish").performClick()

        assertEquals(listOf("previous", "skip", "finish"), actions.calls)
    }

    @Test
    fun cancel_requiresConfirmation() {
        val actions = RecordingRunActions()
        setScreen(runState(status = ExecutionStatus.RUNNING, steps = listOf(stretch)), actions)

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Cancel routine?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep going").performClick()
        composeTestRule.onNodeWithText("Cancel routine?").assertDoesNotExist()
        assertTrue(actions.calls.isEmpty())

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Cancel routine").performClick()

        assertEquals(listOf("cancel"), actions.calls)
    }

    @Test
    fun untimedStep_showsPlaceholderCountdown_andSkipHint() {
        val untimed = TimedStep(UUID.randomUUID(), "Rest", null)
        setScreen(runState(status = ExecutionStatus.RUNNING, steps = listOf(untimed), remaining = null))

        composeTestRule.onNodeWithTag(TAG_RUN_COUNTDOWN).assertTextEquals("--:--")
        composeTestRule.onNodeWithText("No timer for this step. Skip when you are done.").assertIsDisplayed()
    }

    @Test
    fun completed_showsDone_andDoneClosesTheScreen() {
        var closed = false
        setScreen(runState(status = ExecutionStatus.COMPLETED, steps = listOf(stretch)), onClose = { closed = true })

        composeTestRule.onNodeWithText("Routine complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").performClick()

        assertTrue(closed)
    }

    @Test
    fun blockedByAnotherRun_disablesStartAndExplains() {
        setScreen(
            runState(status = ExecutionStatus.NOT_STARTED, steps = listOf(stretch), blocked = true),
        )

        composeTestRule.onNodeWithText("Start").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Another routine is in progress. Resume or cancel it first.").assertIsDisplayed()
    }

    @Test
    fun notificationsOff_explainsThatTheTimerStillRuns() {
        setScreen(runState(status = ExecutionStatus.RUNNING, steps = listOf(stretch)), notificationsAllowed = false)

        composeTestRule.onNodeWithText(
            "Notifications are off. The timer still runs, but you won\u2019t get step alerts.",
        ).assertIsDisplayed()
    }

    @Test
    fun backgroundTimingRefused_asksTheUserToKeepTheAppOpen() {
        setScreen(
            runState(status = ExecutionStatus.RUNNING, steps = listOf(stretch))
                .copy(foregroundUnavailable = true),
        )

        composeTestRule.onNodeWithText(
            "Background timing is unavailable right now. Keep the app open to keep timing.",
        ).assertIsDisplayed()
    }

    @Test
    fun missingRoutine_showsMessage() {
        setScreen(RoutineRunUiState(isMissing = true, isLoading = false))

        composeTestRule.onNodeWithText("This routine no longer exists.").assertIsDisplayed()
    }

    private fun runState(
        status: ExecutionStatus,
        steps: List<TimedStep>,
        remaining: Long? = steps.firstOrNull()?.durationMillis,
        total: Long = steps.sumOf { it.durationMillis ?: 0L },
        blocked: Boolean = false,
    ) = RoutineRunUiState(
        routineTitle = "Morning",
        timer = TimerState(
            status = status,
            steps = steps,
            currentIndex = 0,
            currentElapsedMillis = 0L,
            currentRemainingMillis = remaining,
            totalRemainingMillis = total,
        ),
        isLoading = false,
        blockedByOtherRun = blocked,
    )

    private fun setScreen(
        state: RoutineRunUiState,
        actions: RoutineRunActions = RecordingRunActions(),
        onClose: () -> Unit = {},
        notificationsAllowed: Boolean = true,
    ) {
        composeTestRule.setContent {
            RoutinePilotTheme {
                RoutineRunScreen(
                    state = state,
                    actions = actions,
                    onClose = onClose,
                    notificationsAllowed = notificationsAllowed,
                )
            }
        }
    }

    private class RecordingRunActions : RoutineRunActions {
        val calls = mutableListOf<String>()

        override fun start() {
            calls += "start"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun resume() {
            calls += "resume"
        }

        override fun skip() {
            calls += "skip"
        }

        override fun previous() {
            calls += "previous"
        }

        override fun finish() {
            calls += "finish"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }
}
