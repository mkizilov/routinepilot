package com.routinepilot.infrastructure.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.routinepilot.domain.widget.WidgetCommand
import com.routinepilot.domain.widget.WidgetCommandDispatcher

/**
 * Runs a pause, skip, or finish from a widget button. The app's own coordinator handles it, so the widget gets the
 * same behaviour as the run screen and the notification actions. If the process was killed, the coordinator restores
 * the stored run first. Glance delivers this through its own non-exported receiver.
 */
class WidgetCommandAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = WidgetCommand.fromWire(parameters[COMMAND_KEY])
        val coordinator = context.widgetEntryPoint().runCoordinator()
        WidgetCommandDispatcher.dispatch(
            command = command,
            pause = coordinator::pause,
            skip = coordinator::skip,
            finish = coordinator::finish,
        )
    }

    companion object {
        val COMMAND_KEY = ActionParameters.Key<String>("routinepilot.widget.command")
    }
}
