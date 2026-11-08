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

    @Test
    fun formatCountdown_showsMinutesAndSeconds() {
        assertEquals("00:00", formatCountdown(0L))
        assertEquals("01:05", formatCountdown(65_000L))
        assertEquals("09:59", formatCountdown(599_000L))
    }

    @Test
    fun formatCountdown_roundsUpPartialSeconds() {
        assertEquals("00:01", formatCountdown(1L))
        assertEquals("01:00", formatCountdown(59_001L))
    }

    @Test
    fun formatCountdown_showsHoursWhenNeeded() {
        assertEquals("1:02:03", formatCountdown(3_723_000L))
    }

    @Test
    fun formatCountdown_treatsNegativeTimeAsZero() {
        assertEquals("00:00", formatCountdown(-500L))
    }
}
