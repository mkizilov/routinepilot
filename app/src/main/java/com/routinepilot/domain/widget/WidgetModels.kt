package com.routinepilot.domain.widget

import java.util.UUID

enum class WidgetSize { SMALL, MEDIUM, LARGE }

/**
 * The sizes the widget providers declare, in dp, and the layout bucket for each. Glance's responsive mode gives a
 * widget the largest declared size that fits, so a rendered size normally matches one bucket exactly.
 */
object WidgetSizing {
    const val SMALL_WIDTH_DP = 120f
    const val SMALL_HEIGHT_DP = 110f
    const val MEDIUM_WIDTH_DP = 250f
    const val MEDIUM_HEIGHT_DP = 180f
    const val LARGE_WIDTH_DP = 320f
    const val LARGE_HEIGHT_DP = 260f

    val DECLARED_SIZES_DP: List<Pair<Float, Float>> = listOf(
        SMALL_WIDTH_DP to SMALL_HEIGHT_DP,
        MEDIUM_WIDTH_DP to MEDIUM_HEIGHT_DP,
        LARGE_WIDTH_DP to LARGE_HEIGHT_DP,
    )

    fun bucketOf(widthDp: Float, heightDp: Float): WidgetSize = when {
        widthDp >= LARGE_WIDTH_DP && heightDp >= LARGE_HEIGHT_DP -> WidgetSize.LARGE
        widthDp >= MEDIUM_WIDTH_DP && heightDp >= MEDIUM_HEIGHT_DP -> WidgetSize.MEDIUM
        else -> WidgetSize.SMALL
    }

    /** How many list rows fit in each bucket. */
    fun rowLimit(size: WidgetSize): Int = when (size) {
        WidgetSize.SMALL -> 1
        WidgetSize.MEDIUM -> 2
        WidgetSize.LARGE -> 4
    }
}

/** A command a widget button can send to the running routine. Wire values are what travels in action parameters. */
enum class WidgetCommand(val wire: String) {
    PAUSE("pause"),
    SKIP("skip"),
    FINISH("finish"),
    ;

    companion object {
        fun fromWire(value: String?): WidgetCommand? = entries.firstOrNull { it.wire == value }
    }
}

/** Buttons a run widget may show. Resume and Open start the app, because a foreground start is only allowed there. */
enum class WidgetRunAction { PAUSE, SKIP, FINISH, RESUME_IN_APP, OPEN_APP }

enum class WidgetRunStatus { RUNNING, PAUSED }

data class TodayWidgetItem(
    val title: String,
    val timeLabel: String,
    val isDueNow: Boolean,
    val isMoved: Boolean,
)

/** Today's items, due-now first then upcoming. [totalCount] counts every item, including those past [items]. */
data class TodayWidgetState(
    val items: List<TodayWidgetItem>,
    val totalCount: Int,
) {
    fun visibleFor(size: WidgetSize): List<TodayWidgetItem> = items.take(WidgetSizing.rowLimit(size))

    fun hiddenCountFor(size: WidgetSize): Int = (totalCount - visibleFor(size).size).coerceAtLeast(0)
}

sealed interface RunWidgetState {
    /** No run is stored. */
    data object NoActiveRun : RunWidgetState

    /** A run is stored but its routine was deleted. The app clears it the next time it starts. */
    data object RoutineMissing : RunWidgetState

    /**
     * A run is stored but cannot be shown reliably: its routine plan changed, or it was running when the process that
     * timed it stopped. Opening the app settles it.
     */
    data class NeedsAttention(val routineTitle: String) : RunWidgetState

    data class Active(
        val routineId: UUID,
        val sessionId: UUID,
        val routineTitle: String,
        val status: WidgetRunStatus,
        val stepTitle: String,
        val stepNumber: Int,
        val stepCount: Int,
        /** Null when the step has no timer. */
        val minutesLeftInStep: Int?,
        val minutesLeftTotal: Int,
        val nextStepTitle: String?,
    ) : RunWidgetState
}

data class QuickStartItem(val routineId: UUID, val title: String)

/** Active routines in their saved order. Deleted routines never appear. */
data class QuickStartState(val routines: List<QuickStartItem>) {
    fun visibleFor(size: WidgetSize): List<QuickStartItem> = routines.take(WidgetSizing.rowLimit(size))

    fun hiddenCountFor(size: WidgetSize): Int = (routines.size - visibleFor(size).size).coerceAtLeast(0)
}

object RunWidgetActions {
    /** Which buttons a run widget shows for a state. Only the actions the state can use are included. */
    fun availableFor(state: RunWidgetState): List<WidgetRunAction> = when (state) {
        is RunWidgetState.Active -> when (state.status) {
            WidgetRunStatus.RUNNING -> listOf(
                WidgetRunAction.PAUSE,
                WidgetRunAction.SKIP,
                WidgetRunAction.FINISH,
                WidgetRunAction.OPEN_APP,
            )
            WidgetRunStatus.PAUSED -> listOf(
                WidgetRunAction.RESUME_IN_APP,
                WidgetRunAction.FINISH,
                WidgetRunAction.OPEN_APP,
            )
        }
        RunWidgetState.NoActiveRun, RunWidgetState.RoutineMissing, is RunWidgetState.NeedsAttention ->
            listOf(WidgetRunAction.OPEN_APP)
    }
}

/**
 * Runs a widget command against the running routine. Null, or a value that is not a known command, does nothing.
 * Commands are the only actions a widget sends to the app. They never name a routine, so a widget cannot act on
 * anything except the current run.
 */
object WidgetCommandDispatcher {
    fun dispatch(
        command: WidgetCommand?,
        pause: () -> Unit,
        skip: () -> Unit,
        finish: () -> Unit,
    ) {
        when (command) {
            WidgetCommand.PAUSE -> pause()
            WidgetCommand.SKIP -> skip()
            WidgetCommand.FINISH -> finish()
            null -> Unit
        }
    }
}
