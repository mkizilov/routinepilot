package com.routinepilot.domain.repository

import com.routinepilot.domain.statistics.StatisticsInput
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

interface StatsRepository {
    /**
     * Aggregates for [from]..[to] inclusive, plus routine totals for [consistencyFrom]..[to]. Missed items are those
     * still pending before [now]. Emits again whenever sessions, step completions, or scheduled items change.
     */
    fun observeInput(
        from: LocalDate,
        to: LocalDate,
        consistencyFrom: LocalDate,
        now: Instant,
    ): Flow<StatisticsInput>
}
