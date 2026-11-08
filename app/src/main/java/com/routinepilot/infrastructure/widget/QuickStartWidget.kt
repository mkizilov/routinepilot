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
import androidx.glance.layout.Column
import com.routinepilot.R
import com.routinepilot.domain.widget.QuickStartState
import com.routinepilot.domain.widget.WidgetSizing
import com.routinepilot.infrastructure.notifications.RunIntents

private const val TAG = "QuickStartWidget"

/**
 * One tap starts a routine. The tap opens the run screen, which begins the routine through the same permission path as
 * the Start button. The app never starts a timer from the background.
 */
class QuickStartWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        WidgetSizing.DECLARED_SIZES_DP.map { (width, height) -> DpSize(width.dp, height.dp) }.toSet(),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = runCatching { context.widgetEntryPoint().widgetStateLoader().quickStart() }
            .onFailure { Log.w(TAG, "Could not load routines", it) }
            .getOrNull()
        provideContent {
            if (state == null) WidgetText(str(R.string.widget_load_failed)) else QuickStartContent(state)
        }
    }
}

@Composable
private fun QuickStartContent(state: QuickStartState) {
    val size = currentWidgetSize()
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
        WidgetText(str(R.string.widget_quick_heading), WidgetStyle.heading)
        if (state.routines.isEmpty()) {
            WidgetText(str(R.string.widget_quick_empty))
            OpenAppButton(str(R.string.widget_open))
        } else {
            state.visibleFor(size).forEach { routine ->
                ActivityButton(
                    text = routine.title,
                    intent = RunIntents.activityIntent(context, routine.routineId, RunIntents.ACTION_START_RUN),
                )
            }
            val hidden = state.hiddenCountFor(size)
            if (hidden > 0) WidgetText(str(R.string.widget_more, hidden))
        }
    }
}

class QuickStartWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickStartWidget()
}
