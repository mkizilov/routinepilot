package com.routinepilot.presentation.routine

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutineFormattingTest {
    @Test
    fun formatDuration_showsMinutesUnderAnHour() {
        assertEquals("0 min", formatDuration(0))
        assertEquals("35 min", formatDuration(35 * 60))
    }

    @Test
    fun formatDuration_showsHoursAndMinutes() {
        assertEquals("1 h", formatDuration(60 * 60))
        assertEquals("1 h 5 min", formatDuration(65 * 60))
        assertEquals("2 h 30 min", formatDuration(150 * 60))
    }
}
