package com.routinepilot.domain.statistics

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

/**
 * Every statistic is defined here, in one place.
 *
 * Completion: completed scheduled items divided by (completed + missed). Skipped items are excluded. Items not yet
 * due are excluded. Null when nothing was due.
 *
 * Completed routine session: a session that ended with Finish. Cancelled sessions are counted separately and never
 * as completed. Sessions with an unrecorded outcome from before schema version 5 count as neither.
 *
 * Completed steps: step completions on the day, whatever the outcome of their session.
 *
 * Focused time: running time, excluding pauses, of sessions that were finished or cancelled that day.
 *
 * Day status, which drives streaks:
 * - KEPT: at least one session finished, or at least one scheduled item completed.
 * - EXCUSED: nothing kept, at least one item skipped, and nothing missed.
 * - IN_PROGRESS: today, nothing kept, nothing missed yet. Today never breaks a streak until something is missed.
 * - BROKEN: anything else before today, or today with something missed. A cancelled session alone does not keep a day.
 * - FUTURE: after today, never counted.
 *
 * Current streak: consecutive KEPT days ending today, where EXCUSED and IN_PROGRESS days are transparent. A BROKEN
 * day ends the streak. Longest streak: the longest such run in the lookback window. Both are capped at
 * [LOOKBACK_DAYS], so a longer streak is reported as that many days.
 *
 * Routine consistency: per routine, over the last [CONSISTENCY_DAYS] days, completed / (completed + missed).
 *
 * Weeks run Monday to Sunday. Months are calendar months. Weeks and months that include today or later are marked
 * partial. Their totals are for the days so far.
 */
object StatisticsCalculator {
    const val LOOKBACK_DAYS = 365
    const val CONSISTENCY_DAYS = 30
    private const val TREND_WEEKS = 12
    private const val TREND_MONTHS = 12
    private const val CALENDAR_WEEKS = 5
    private const val LAST_DAYS = 7
    private const val DAYS_TO_WEEK_END = 6L
    private const val PERCENT = 100.0

    fun compute(
        input: StatisticsInput,
        today: LocalDate,
    ): Statistics {
        val windowStart = today.minusDays(LOOKBACK_DAYS - 1L)
        val window = (0 until LOOKBACK_DAYS).map { dayOf(windowStart.plusDays(it.toLong()), input, today) }
        val (current, longest) = streaks(window.map { it.status })
        val monday = mondayOf(today)
        val calendarStart = monday.minusWeeks(CALENDAR_WEEKS - 1L)
        return Statistics(
            today = dayOf(today, input, today),
            last7Days = period(today.minusDays(LAST_DAYS - 1L), today, input, today),
            weeks =
                (TREND_WEEKS - 1 downTo 0).map { weeksAgo ->
                    val start = monday.minusWeeks(weeksAgo.toLong())
                    period(start, start.plusDays(DAYS_TO_WEEK_END), input, today)
                },
            months =
                (TREND_MONTHS - 1 downTo 0).map { monthsAgo ->
                    val month = YearMonth.from(today).minusMonths(monthsAgo.toLong())
                    period(month.atDay(1), month.atEndOfMonth(), input, today)
                },
            calendar =
                (0 until CALENDAR_WEEKS * 7).map {
                    dayOf(
                        calendarStart.plusDays(it.toLong()),
                        input,
                        today,
                    )
                },
            currentStreak = current,
            longestStreak = longest,
            consistency =
                input.routines.map {
                    val due = it.completed + it.missed
                    RoutineConsistency(it.routineId, it.completed, it.missed, percentOf(it.completed, due))
                },
        )
    }

    internal fun statusOf(
        date: LocalDate,
        occ: OccurrenceTotals,
        sess: SessionTotals,
        today: LocalDate,
    ): DayStatus =
        when {
            date.isAfter(today) -> DayStatus.FUTURE
            sess.finished >= 1 || occ.completed >= 1 -> DayStatus.KEPT
            date == today && occ.missed == 0 -> DayStatus.IN_PROGRESS
            occ.skipped >= 1 && occ.missed == 0 -> DayStatus.EXCUSED
            else -> DayStatus.BROKEN
        }

    /** Returns (current, longest). [statuses] runs oldest first and ends today. */
    internal fun streaks(statuses: List<DayStatus>): Pair<Int, Int> {
        var current = 0
        for (status in statuses.asReversed()) {
            when (status) {
                DayStatus.KEPT -> current++
                DayStatus.BROKEN -> break
                DayStatus.EXCUSED, DayStatus.IN_PROGRESS, DayStatus.FUTURE -> Unit
            }
        }
        var run = 0
        var longest = 0
        for (status in statuses) {
            when (status) {
                DayStatus.KEPT -> {
                    run++
                    if (run > longest) longest = run
                }
                DayStatus.BROKEN -> run = 0
                DayStatus.EXCUSED, DayStatus.IN_PROGRESS, DayStatus.FUTURE -> Unit
            }
        }
        return current to longest
    }

    internal fun percentOf(
        completed: Int,
        due: Int,
    ): Int? = if (due == 0) null else (completed * PERCENT / due).roundToInt()

    private fun dayOf(
        date: LocalDate,
        input: StatisticsInput,
        today: LocalDate,
    ): DayStats {
        val occ = input.occurrences[date] ?: OccurrenceTotals.NONE
        val sess = input.sessions[date] ?: SessionTotals.NONE
        return DayStats(
            date = date,
            completed = occ.completed,
            skipped = occ.skipped,
            missed = occ.missed,
            finishedSessions = sess.finished,
            cancelledSessions = sess.cancelled,
            completedSteps = input.steps[date] ?: 0,
            focusedMillis = sess.focusedMillis,
            completionPercent = percentOf(occ.completed, occ.completed + occ.missed),
            status = statusOf(date, occ, sess, today),
        )
    }

    private fun period(
        start: LocalDate,
        endInclusive: LocalDate,
        input: StatisticsInput,
        today: LocalDate,
    ): PeriodStats {
        var completed = 0
        var missed = 0
        var finished = 0
        var steps = 0
        var focused = 0L
        var date = start
        while (!date.isAfter(endInclusive)) {
            val occ = input.occurrences[date] ?: OccurrenceTotals.NONE
            val sess = input.sessions[date] ?: SessionTotals.NONE
            completed += occ.completed
            missed += occ.missed
            finished += sess.finished
            steps += input.steps[date] ?: 0
            focused += sess.focusedMillis
            date = date.plusDays(1)
        }
        return PeriodStats(
            start = start,
            endInclusive = endInclusive,
            completionPercent = percentOf(completed, completed + missed),
            finishedSessions = finished,
            completedSteps = steps,
            focusedMillis = focused,
            partial = !endInclusive.isBefore(today),
        )
    }

    private fun mondayOf(date: LocalDate): LocalDate = date.minusDays(date.dayOfWeek.value - 1L)
}
