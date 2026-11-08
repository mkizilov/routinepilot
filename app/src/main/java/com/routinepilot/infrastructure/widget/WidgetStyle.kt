package com.routinepilot.infrastructure.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

private const val INK_DAY = 0xFF1C1B1FL
private const val INK_NIGHT = 0xFFE6E1E5L

/** Text colors for every launcher. They follow the system light or dark setting, not Material You. */
internal object WidgetStyle {
    private val ink = DayNightColor(day = Color(INK_DAY), night = Color(INK_NIGHT))
    val body = TextStyle(color = ink, fontSize = 13.sp)
    val heading = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

/**
 * Picks the day or night color from the system night mode. Glance 1.2 provides no day and night factory, so the color
 * provider is written here against the library's single-method interface.
 */
private class DayNightColor(
    private val day: Color,
    private val night: Color,
) : ColorProvider {
    override fun getColor(context: Context): Color {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_YES) night else day
    }
}
