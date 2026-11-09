package com.routinepilot.presentation.schedule

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.schedule.Agenda
import com.routinepilot.domain.schedule.AgendaItem
import com.routinepilot.domain.schedule.AgendaKind
import com.routinepilot.presentation.theme.RoutinePilotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class AgendaScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val utc = ZoneId.of("UTC")

    @Test
    fun loadedWithNothingScheduled_showsEmptyStateAndHidesSections() {
        setScreen(AgendaUiState(loaded = true, zone = utc))

        composeTestRule.onNodeWithTag(TAG_AGENDA_EMPTY).assertIsDisplayed()
    }

    @Test
    fun sectionsAppearWithTheirItems() {
        val due = item("Stretch", "2026-03-10T11:30:00Z", OccurrenceStatus.PLANNED)
        val later = item("Read", "2026-03-10T18:00:00Z", OccurrenceStatus.PLANNED)
        val done = item("Brush", "2026-03-10T08:00:00Z", OccurrenceStatus.COMPLETED)
        setScreen(
            AgendaUiState(
                loaded = true,
                zone = utc,
                agenda = Agenda(current = listOf(due), upcoming = listOf(later), earlier = listOf(done)),
            ),
        )

        composeTestRule.onNodeWithText("Due now").assertIsDisplayed()
        composeTestRule.onNodeWithText("Upcoming").assertIsDisplayed()
        composeTestRule.onNodeWithText("Earlier today").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stretch").assertIsDisplayed()
        composeTestRule.onNodeWithText("Completed").assertIsDisplayed()
    }

    @Test
    fun doneAction_onAPendingItem_requestsCompletion() {
        val due = item("Stretch", "2026-03-10T11:30:00Z", OccurrenceStatus.PLANNED)
        val actions = RecordingAgendaActions()
        val agenda = Agenda(listOf(due), emptyList(), emptyList())
        setScreen(AgendaUiState(loaded = true, zone = utc, agenda = agenda), actions)

        composeTestRule.onNodeWithText("Done").performClick()

        assertEquals(listOf("complete:${due.occurrenceId}"), actions.calls)
    }

    @Test
    fun postpone_requestsTheCurrentInstantSoTheMoveIsUnambiguous() {
        val due = item("Stretch", "2026-03-10T11:30:00Z", OccurrenceStatus.PLANNED)
        val actions = RecordingAgendaActions()
        val agenda = Agenda(listOf(due), emptyList(), emptyList())
        setScreen(AgendaUiState(loaded = true, zone = utc, agenda = agenda), actions)

        composeTestRule.onNodeWithText("+15 min").performClick()

        assertEquals(listOf("postpone:${due.occurrenceId}:${due.scheduledAt}"), actions.calls)
    }

    @Test
    fun earlierItems_offerNoActions() {
        val done = item("Brush", "2026-03-10T08:00:00Z", OccurrenceStatus.COMPLETED)
        setScreen(AgendaUiState(loaded = true, zone = utc, agenda = Agenda(emptyList(), emptyList(), listOf(done))))

        composeTestRule.onNodeWithText("Done").assertDoesNotExist()
        composeTestRule.onNodeWithText("+15 min").assertDoesNotExist()
    }

    private fun item(
        title: String,
        at: String,
        status: OccurrenceStatus,
    ) = AgendaItem(
        occurrenceId = UUID.randomUUID(),
        title = title,
        kind = AgendaKind.ROUTINE,
        scheduledAt = Instant.parse(at),
        originalScheduledAt = Instant.parse(at),
        status = status,
    )

    private fun setScreen(
        state: AgendaUiState,
        actions: AgendaActions = RecordingAgendaActions(),
    ) {
        composeTestRule.setContent {
            RoutinePilotTheme {
                AgendaScreen(state = state, actions = actions, onNewSchedule = {}, onOpenSchedules = {})
            }
        }
    }

    private class RecordingAgendaActions : AgendaActions {
        val calls = mutableListOf<String>()

        override fun complete(occurrenceId: UUID) {
            calls += "complete:$occurrenceId"
        }

        override fun skip(occurrenceId: UUID) {
            calls += "skip:$occurrenceId"
        }

        override fun postpone(
            occurrenceId: UUID,
            currentTime: Instant,
        ) {
            calls += "postpone:$occurrenceId:$currentTime"
        }
    }
}
