package com.routinepilot.domain.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class WidgetModelsTest {
    @Test
    fun declaredSizes_eachMapToTheBucketItWasDeclaredFor() {
        assertEquals(WidgetSize.SMALL, WidgetSizing.bucketOf(120f, 110f))
        assertEquals(WidgetSize.MEDIUM, WidgetSizing.bucketOf(250f, 180f))
        assertEquals(WidgetSize.LARGE, WidgetSizing.bucketOf(320f, 260f))
        assertEquals(3, WidgetSizing.DECLARED_SIZES_DP.size)
    }

    @Test
    fun unevenSizes_fallToTheBucketThatFullyFits() {
        assertEquals(WidgetSize.MEDIUM, WidgetSizing.bucketOf(300f, 200f))
        assertEquals(WidgetSize.MEDIUM, WidgetSizing.bucketOf(330f, 200f))
        assertEquals(WidgetSize.SMALL, WidgetSizing.bucketOf(330f, 150f))
    }

    @Test
    fun rowLimit_growsWithTheBucket() {
        assertEquals(1, WidgetSizing.rowLimit(WidgetSize.SMALL))
        assertEquals(2, WidgetSizing.rowLimit(WidgetSize.MEDIUM))
        assertEquals(4, WidgetSizing.rowLimit(WidgetSize.LARGE))
    }

    @Test
    fun commands_roundTripThroughTheirWireValue_andUnknownValuesAreRejected() {
        assertEquals(WidgetCommand.PAUSE, WidgetCommand.fromWire("pause"))
        assertEquals(WidgetCommand.SKIP, WidgetCommand.fromWire("skip"))
        assertEquals(WidgetCommand.FINISH, WidgetCommand.fromWire("finish"))
        assertNull(WidgetCommand.fromWire("delete"))
        assertNull(WidgetCommand.fromWire(""))
        assertNull(WidgetCommand.fromWire(null))
    }

    @Test
    fun runningRoutine_offersPauseSkipFinishAndOpen() {
        assertEquals(
            listOf(WidgetRunAction.PAUSE, WidgetRunAction.SKIP, WidgetRunAction.FINISH, WidgetRunAction.OPEN_APP),
            RunWidgetActions.availableFor(active(WidgetRunStatus.RUNNING)),
        )
    }

    @Test
    fun pausedRoutine_offersResumeInTheAppFinishAndOpen_notSkip() {
        assertEquals(
            listOf(WidgetRunAction.RESUME_IN_APP, WidgetRunAction.FINISH, WidgetRunAction.OPEN_APP),
            RunWidgetActions.availableFor(active(WidgetRunStatus.PAUSED)),
        )
    }

    @Test
    fun problemStates_onlyOfferToOpenTheApp() {
        assertEquals(listOf(WidgetRunAction.OPEN_APP), RunWidgetActions.availableFor(RunWidgetState.NoActiveRun))
        assertEquals(listOf(WidgetRunAction.OPEN_APP), RunWidgetActions.availableFor(RunWidgetState.RoutineMissing))
        assertEquals(
            listOf(WidgetRunAction.OPEN_APP),
            RunWidgetActions.availableFor(RunWidgetState.NeedsAttention("Morning")),
        )
    }

    @Test
    fun dispatcher_runsOnlyTheMatchingCommand() {
        val calls = mutableListOf<String>()
        val pause = { calls += "pause" }
        val skip = { calls += "skip" }
        val finish = { calls += "finish" }

        WidgetCommandDispatcher.dispatch(WidgetCommand.SKIP, pause, skip, finish)
        WidgetCommandDispatcher.dispatch(WidgetCommand.FINISH, pause, skip, finish)

        assertEquals(listOf("skip", "finish"), calls)
    }

    @Test
    fun dispatcher_ignoresAMissingOrUnknownCommand() {
        val calls = mutableListOf<String>()
        val any = { calls += "any" }

        WidgetCommandDispatcher.dispatch(null, any, any, any)

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun todayAndQuickStart_hideRowsBeyondTheSizeLimit() {
        val today =
            TodayWidgetState(
                items = List(4) { TodayWidgetItem("Item $it", "07:00", isDueNow = false, isMoved = false) },
                totalCount = 6,
            )
        assertEquals(1, today.visibleFor(WidgetSize.SMALL).size)
        assertEquals(5, today.hiddenCountFor(WidgetSize.SMALL))
        assertEquals(4, today.hiddenCountFor(WidgetSize.MEDIUM))
        assertEquals(2, today.hiddenCountFor(WidgetSize.LARGE))

        val quick = QuickStartState(List(6) { QuickStartItem(UUID.randomUUID(), "Routine $it") })
        assertEquals(1, quick.visibleFor(WidgetSize.SMALL).size)
        assertEquals(5, quick.hiddenCountFor(WidgetSize.SMALL))
        assertEquals(2, quick.hiddenCountFor(WidgetSize.LARGE))
    }

    private fun active(status: WidgetRunStatus) =
        RunWidgetState.Active(
            routineId = UUID.randomUUID(),
            sessionId = UUID.randomUUID(),
            routineTitle = "Morning",
            status = status,
            stepTitle = "Stretch",
            stepNumber = 1,
            stepCount = 2,
            minutesLeftInStep = 1,
            minutesLeftTotal = 2,
            nextStepTitle = "Water",
        )
}
