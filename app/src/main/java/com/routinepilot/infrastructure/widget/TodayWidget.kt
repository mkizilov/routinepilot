package com.routinepilot.infrastructure.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import com.routinepilot.R
import com.routinepilot.domain.widget.TodayWidgetItem
import com.routinepilot.domain.widget.TodayWidgetState
import com.routinepilot.domain.widget.WidgetSizing

private const val TAG = "TodayWidget"

/** Today's next routines and tasks. Read-only: a tap opens the app. */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode =
        SizeMode.Responsive(
            WidgetSizing.DECLARED_SIZES_DP.map { (width, height) -> DpSize(width.dp, height.dp) }.toSet(),
        )

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val state =
            runCatching { context.widgetEntryPoint().widgetStateLoader().today() }
                .onFailure { Log.w(TAG, "Could not load today's items", it) }
                .getOrNull()
        provideContent {
            if (state == null) WidgetText(str(R.string.widget_load_failed)) else TodayContent(state)
        }
    }
}

@Composable
private fun TodayContent(state: TodayWidgetState) {
    val size = currentWidgetSize()
    Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
        WidgetText(str(R.string.widget_today_heading), WidgetStyle.heading)
        if (state.items.isEmpty()) {
            WidgetText(str(R.string.widget_today_empty))
        } else {
            state.visibleFor(size).forEach { TodayRow(it) }
            val hidden = state.hiddenCountFor(size)
            if (hidden > 0) WidgetText(str(R.string.widget_more, hidden))
        }
    }
}

@Composable
private fun TodayRow(item: TodayWidgetItem) {
    val due = if (item.isDueNow) str(R.string.widget_due_now) + " " else ""
    val moved = if (item.isMoved) " " + str(R.string.widget_moved) else ""
    WidgetText(due + item.timeLabel + "  " + item.title + moved)
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
