package com.routinepilot.presentation.routine

private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60

fun formatDuration(totalSeconds: Int): String {
    val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours == 0 -> "$minutes min"
        minutes == 0 -> "$hours h"
        else -> "$hours h $minutes min"
    }
}
