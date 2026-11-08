package com.routinepilot.domain.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Today is Wednesday 2026-11-04, so this week runs Monday 2026-11-02 to Sunday 2026-11-08. Expected values were
 * checked against the calendar before being written down.
 */
class StatisticsCalculatorTest {
    private val today = LocalDate.of(2026, 11, 4)

    private fun day(ago: Int): LocalDate = today.minusDays(ago.toLong())

    private fun input(
        occurrences: Map<LocalDate, OccurrenceTotals> = emptyMap(),
        sessions: Map<LocalDate, SessionTotals> = emptyMap(),
        steps: Map<LocalDate, Int> = emptyMap(),
        routines: List<RoutineTotals> = emptyList(),
    ) = StatisticsInput(occurrences, sessions, steps, routines)

    private fun kept(vararg days: Int): Map<LocalDate, OccurrenceTotals> =
        days.associate { day(it) to OccurrenceTotals(completed = 1, skipped = 0, missed = 0) }

    private fun streakOf(stats: Statistics) = stats.currentStreak to stats.longestStreak

    // Completion and day totals

    @Test
    fun completion_isCompletedOverCompletedPlusMissed_andSkippedIsLeftOut() {
        val stats =
            StatisticsCalculator.compute(
                input(occurrences = mapOf(today to OccurrenceTotals(completed = 3, skipped = 2, missed = 1))),
                today,
            )

        assertEquals(75, stats.today.completionPercent)
    }

    @Test
    fun completion_isNullWhenOnlySkippedItemsExist() {
        val stats =
            StatisticsCalculator.compute(
                input(occurrences = mapOf(today to OccurrenceTotals(completed = 0, skipped = 2, missed = 0))),
                today,
            )

        assertNull(stats.today.completionPercent)
    }

    @Test
    fun partialCompletion_roundsToTheNearestPercent() {
        val stats =
            StatisticsCalculator.compute(
                input(occurrences = mapOf(today to OccurrenceTotals(completed = 2, skipped = 0, missed = 1))),
                today,
            )

        assertEquals(67, stats.today.completionPercent)
    }

    @Test
    fun partialCompletion_keepsTheDayWhenAnyItemIsDone() {
        val yesterday = day(1)
        val stats =
            StatisticsCalculator.compute(
                input(occurrences = mapOf(yesterday to OccurrenceTotals(completed = 1, skipped = 0, missed = 2))),
                today,
            )

        val dayStats = stats.calendar.single { it.date == yesterday }
        assertEquals(DayStatus.KEPT, dayStats.status)
        assertEquals(33, dayStats.completionPercent)
    }

    @Test
    fun sessions_onlyFinishedCountsAsCompletedCancelledIsCountedApart() {
        val yesterday = day(1)
        val stats =
            StatisticsCalculator.compute(
                input(sessions = mapOf(yesterday to SessionTotals(finished = 1, cancelled = 2, focusedMillis = 0L))),
                today,
            )

        val dayStats = stats.calendar.single { it.date == yesterday }
        assertEquals(1, dayStats.finishedSessions)
        assertEquals(2, dayStats.cancelledSessions)
    }

    @Test
    fun completedSteps_areCountedPerDay() {
        val stats = StatisticsCalculator.compute(input(steps = mapOf(today to 7)), today)

        assertEquals(7, stats.today.completedSteps)
    }

    @Test
    fun focusedTime_isSummedOverTheWeekAcrossFinishedAndCancelledSessions() {
        val monday = LocalDate.of(2026, 11, 2)
        val tuesday = LocalDate.of(2026, 11, 3)
        val stats =
            StatisticsCalculator.compute(
                input(
                    sessions =
                        mapOf(
                            monday to SessionTotals(finished = 1, cancelled = 0, focusedMillis = 600_000L),
                            tuesday to SessionTotals(finished = 0, cancelled = 1, focusedMillis = 300_000L),
                        ),
                ),
                today,
            )

        assertEquals(900_000L, stats.weeks.last().focusedMillis)
        assertEquals(900_000L, stats.last7Days.focusedMillis)
    }

    @Test
    fun periodTotals_countEachDayOnceAndRespectTheWindow() {
        val stats =
            StatisticsCalculator.compute(
                input(
                    occurrences =
                        mapOf(
                            LocalDate.of(2026, 10, 28) to OccurrenceTotals(completed = 5, skipped = 0, missed = 0),
                            LocalDate.of(2026, 10, 29) to OccurrenceTotals(completed = 2, skipped = 0, missed = 0),
                            today to OccurrenceTotals(completed = 1, skipped = 0, missed = 1),
                        ),
                    sessions = mapOf(LocalDate.of(2026, 11, 1) to SessionTotals(1, 0, 0L)),
                ),
                today,
            )

        assertEquals(75, stats.last7Days.completionPercent)
        assertEquals(1, stats.last7Days.finishedSessions)
    }

    // Streak edge cases

    @Test
    fun streak_countsConsecutiveKeptDaysEndingToday() {
        val stats = StatisticsCalculator.compute(input(occurrences = kept(0, 1, 2)), today)

        assertEquals(3 to 3, streakOf(stats))
    }

    @Test
    fun streak_todayInProgressDoesNotBreakTheStreakFromYesterday() {
        val stats = StatisticsCalculator.compute(input(occurrences = kept(1, 2)), today)

        assertEquals(DayStatus.IN_PROGRESS, stats.today.status)
        assertEquals(2 to 2, streakOf(stats))
    }

    @Test
    fun streak_skippedDayIsTransparent() {
        val stats =
            StatisticsCalculator.compute(
                input(
                    occurrences =
                        kept(
                            0,
                            2,
                        ) + mapOf(day(1) to OccurrenceTotals(completed = 0, skipped = 1, missed = 0)),
                ),
                today,
            )

        assertEquals(DayStatus.EXCUSED, stats.calendar.single { it.date == day(1) }.status)
        assertEquals(2 to 2, streakOf(stats))
    }

    @Test
    fun streak_missedItemBreaksIt() {
        val stats =
            StatisticsCalculator.compute(
                input(
                    occurrences =
                        kept(
                            0,
                            2,
                        ) + mapOf(day(1) to OccurrenceTotals(completed = 0, skipped = 0, missed = 1)),
                ),
                today,
            )

        assertEquals(DayStatus.BROKEN, stats.calendar.single { it.date == day(1) }.status)
        assertEquals(1 to 1, streakOf(stats))
    }

    @Test
    fun streak_todayWithAMissedItemBreaksTheCurrentStreakButNotTheLongest() {
        val stats =
            StatisticsCalculator.compute(
                input(
                    occurrences = kept(1, 2) + mapOf(today to OccurrenceTotals(completed = 0, skipped = 0, missed = 1)),
                ),
                today,
            )

        assertEquals(DayStatus.BROKEN, stats.today.status)
        assertEquals(0 to 2, streakOf(stats))
    }

    @Test
    fun streak_aDayWithNoDataBreaksIt() {
        val stats = StatisticsCalculator.compute(input(occurrences = kept(0)), today)

        assertEquals(1 to 1, streakOf(stats))
    }

    @Test
    fun streak_aCancelledOnlyDayBreaksItAndDoesNotCount() {
        val stats =
            StatisticsCalculator.compute(
                input(
                    occurrences = kept(0),
                    sessions = mapOf(day(1) to SessionTotals(finished = 0, cancelled = 1, focusedMillis = 0L)),
                ),
                today,
            )

        assertEquals(DayStatus.BROKEN, stats.calendar.single { it.date == day(1) }.status)
        assertEquals(1 to 1, streakOf(stats))
    }

    @Test
    fun streak_futureDataNeverExtendsIt() {
        val tomorrow = today.plusDays(1)
        val stats =
            StatisticsCalculator.compute(
                input(
                    occurrences = kept(0),
                    sessions = mapOf(tomorrow to SessionTotals(finished = 1, cancelled = 0, focusedMillis = 0L)),
                ),
                today,
            )

        assertEquals(DayStatus.FUTURE, stats.calendar.single { it.date == tomorrow }.status)
        assertEquals(1 to 1, streakOf(stats))
    }

    @Test
    fun streak_longestRunIsTrackedSeparatelyFromTheCurrentOne() {
        val stats = StatisticsCalculator.compute(input(occurrences = kept(0, 1, 7, 8, 9, 10)), today)

        assertEquals(2 to 4, streakOf(stats))
    }

    @Test
    fun streak_isCappedAtTheLookbackWindow() {
        val everyDay = (0 until 400).associate { day(it) to OccurrenceTotals(1, 0, 0) }

        val stats = StatisticsCalculator.compute(input(occurrences = everyDay), today)

        assertEquals(StatisticsCalculator.LOOKBACK_DAYS to StatisticsCalculator.LOOKBACK_DAYS, streakOf(stats))
    }

    @Test
    fun streak_withNoHistoryIsZeroAndTodayIsInProgress() {
        val stats = StatisticsCalculator.compute(StatisticsInput.EMPTY, today)

        assertEquals(0 to 0, streakOf(stats))
        assertEquals(DayStatus.IN_PROGRESS, stats.today.status)
        assertNull(stats.today.completionPercent)
    }

    // Recurring routines

    @Test
    fun recurringRoutine_aMissedFridayShowsInItsWeekAndBreaksTheStreak() {
        val friday = LocalDate.of(2026, 10, 30)
        val stats =
            StatisticsCalculator.compute(
                input(
                    occurrences =
                        kept(0, 1, 2) + mapOf(friday to OccurrenceTotals(completed = 0, skipped = 0, missed = 1)),
                ),
                today,
            )

        assertEquals(100, stats.weeks.last().completionPercent)
        assertEquals(0, stats.weeks[10].completionPercent)
        assertEquals(3 to 3, streakOf(stats))
    }

    @Test
    fun consistency_isCompletedOverDueWithinEachRoutine() {
        val withDue = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val nothingDue = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val stats =
            StatisticsCalculator.compute(
                input(
                    routines =
                        listOf(
                            RoutineTotals(withDue, completed = 3, missed = 1),
                            RoutineTotals(nothingDue, completed = 0, missed = 0),
                        ),
                ),
                today,
            )

        assertEquals(75, stats.consistency.single { it.routineId == withDue }.percent)
        assertNull(stats.consistency.single { it.routineId == nothingDue }.percent)
    }

    // Trend windows

    @Test
    fun weeks_areMondayToSundayAndTheCurrentWeekIsPartial() {
        val stats = StatisticsCalculator.compute(StatisticsInput.EMPTY, today)

        assertEquals(12, stats.weeks.size)
        assertEquals(LocalDate.of(2026, 11, 2), stats.weeks.last().start)
        assertEquals(LocalDate.of(2026, 11, 8), stats.weeks.last().endInclusive)
        assertEquals(true, stats.weeks.last().partial)
        assertEquals(LocalDate.of(2026, 10, 26), stats.weeks[10].start)
        assertEquals(false, stats.weeks[10].partial)
    }

    @Test
    fun months_coverTwelveCalendarMonthsEndingWithTheCurrentOne() {
        val stats = StatisticsCalculator.compute(StatisticsInput.EMPTY, today)

        assertEquals(12, stats.months.size)
        assertEquals(LocalDate.of(2025, 12, 1), stats.months.first().start)
        assertEquals(LocalDate.of(2026, 11, 1), stats.months.last().start)
        assertEquals(LocalDate.of(2026, 11, 30), stats.months.last().endInclusive)
        assertEquals(true, stats.months.last().partial)
    }

    @Test
    fun calendar_isFiveWeeksEndingWithThisWeekAndFutureDaysAreMarked() {
        val stats = StatisticsCalculator.compute(StatisticsInput.EMPTY, today)

        assertEquals(35, stats.calendar.size)
        assertEquals(LocalDate.of(2026, 10, 5), stats.calendar.first().date)
        assertEquals(LocalDate.of(2026, 11, 8), stats.calendar.last().date)
        assertEquals(DayStatus.FUTURE, stats.calendar.last().status)
        assertEquals(DayStatus.IN_PROGRESS, stats.calendar.single { it.date == today }.status)
    }
}
