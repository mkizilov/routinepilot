package com.routinepilot.infrastructure.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import com.routinepilot.R
import com.routinepilot.domain.widget.RunWidgetActions
import com.routinepilot.domain.widget.RunWidgetState
import com.routinepilot.domain.widget.WidgetCommand
import com.routinepilot.domain.widget.WidgetRunAction
import com.routinepilot.domain.widget.WidgetRunStatus
import com.routinepilot.domain.widget.WidgetSize
import com.routinepilot.domain.widget.WidgetSizing
import com.routinepilot.infrastructure.notifications.RunIntents
import java.util.UUID

private const val TAG = "RunWidget"

/**
 * The running routine: step, time left, and controls. The countdown is accurate to the minute and refreshes every
 * minute while the app process is alive. Launchers cannot tick a widget every second.
 */
class RunWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode =
        SizeMode.Responsive(
            WidgetSizing.DECLARED_SIZES_DP.map { (width, height) -> DpSize(width.dp, height.dp) }.toSet(),
        )

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val state =
            runCatching { context.widgetEntryPoint().widgetStateLoader().run() }
                .onFailure { Log.w(TAG, "Could not load the running routine", it) }
                .getOrNull()
        provideContent {
            if (state == null) WidgetText(str(R.string.widget_load_failed)) else RunContent(state)
        }
    }
}

@Composable
private fun RunContent(state: RunWidgetState) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
        when (state) {
            RunWidgetState.NoActiveRun -> {
                WidgetText(str(R.string.widget_run_none))
                OpenAppButton(str(R.string.widget_open))
            }
            RunWidgetState.RoutineMissing -> {
                WidgetText(str(R.string.widget_run_missing))
                OpenAppButton(str(R.string.widget_open))
            }
            is RunWidgetState.NeedsAttention -> {
                WidgetText(str(R.string.widget_run_attention, state.routineTitle))
                OpenAppButton(str(R.string.widget_open))
            }
            is RunWidgetState.Active -> ActiveRunContent(state, currentWidgetSize())
        }
    }
}

@Composable
private fun ActiveRunContent(
    state: RunWidgetState.Active,
    size: WidgetSize,
) {
    WidgetText(state.routineTitle, WidgetStyle.heading)
    val status =
        if (state.status == WidgetRunStatus.RUNNING) {
            str(R.string.widget_run_running)
        } else {
            str(R.string.widget_run_paused)
        }
    WidgetText(status)
    WidgetText(str(R.string.widget_step_of, state.stepNumber, state.stepCount, state.stepTitle))
    if (size != WidgetSize.SMALL) {
        state.minutesLeftInStep?.let { WidgetText(str(R.string.widget_minutes_left, it)) }
        WidgetText(str(R.string.widget_total_left, state.minutesLeftTotal))
    }
    if (size == WidgetSize.LARGE) {
        state.nextStepTitle?.let { WidgetText(str(R.string.widget_next, it)) }
    }
    val actions = RunWidgetActions.availableFor(state)
    val shown = if (size == WidgetSize.SMALL) actions.take(1) else actions
    Row {
        shown.forEach { RunActionButton(it, state.routineId) }
    }
}

@Composable
private fun RunActionButton(
    action: WidgetRunAction,
    routineId: UUID,
) {
    val context = LocalContext.current
    when (action) {
        WidgetRunAction.PAUSE -> CommandButton(str(R.string.widget_pause), WidgetCommand.PAUSE)
        WidgetRunAction.SKIP -> CommandButton(str(R.string.widget_skip), WidgetCommand.SKIP)
        WidgetRunAction.FINISH -> CommandButton(str(R.string.widget_finish), WidgetCommand.FINISH)
        WidgetRunAction.RESUME_IN_APP ->
            ActivityButton(
                text = str(R.string.widget_resume),
                intent = RunIntents.activityIntent(context, routineId, RunIntents.ACTION_RESUME_RUN),
            )
        WidgetRunAction.OPEN_APP -> OpenAppButton(str(R.string.widget_open))
    }
}

class RunWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RunWidget()
}
