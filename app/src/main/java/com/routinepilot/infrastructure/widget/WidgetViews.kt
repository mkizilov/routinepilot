package com.routinepilot.infrastructure.widget

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.glance.Button
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.routinepilot.MainActivity
import com.routinepilot.domain.widget.WidgetCommand
import com.routinepilot.domain.widget.WidgetSize
import com.routinepilot.domain.widget.WidgetSizing

@Composable
internal fun str(
    @StringRes id: Int,
    vararg args: Any,
): String = LocalContext.current.getString(id, *args)

/** The layout bucket for the size the launcher gave this widget. */
@Composable
internal fun currentWidgetSize(): WidgetSize {
    val size = LocalSize.current
    return WidgetSizing.bucketOf(size.width.value, size.height.value)
}

@Composable
internal fun WidgetText(
    text: String,
    style: TextStyle = WidgetStyle.body,
) {
    Text(text = text, style = style)
}

/** Sends a command to the running routine. The command travels as a parameter and is checked on arrival. */
@Composable
internal fun CommandButton(
    text: String,
    command: WidgetCommand,
) {
    Button(
        text = text,
        onClick =
            actionRunCallback<WidgetCommandAction>(
                actionParametersOf(WidgetCommandAction.COMMAND_KEY to command.wire),
            ),
    )
}

/** Opens the app through an explicit intent to MainActivity. Used for Start, Resume, and the run screen. */
@Composable
internal fun ActivityButton(
    text: String,
    intent: Intent,
) {
    Button(text = text, onClick = actionStartActivity(intent))
}

@Composable
internal fun OpenAppButton(text: String) {
    val context = LocalContext.current
    Button(text = text, onClick = actionStartActivity(Intent(context, MainActivity::class.java)))
}
