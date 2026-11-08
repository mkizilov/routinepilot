package com.routinepilot.testing

import com.routinepilot.domain.repository.StatsRepository
import com.routinepilot.domain.statistics.StatisticsInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate

/** Test double whose history can be changed to simulate sessions and items being recorded. */
class FakeStatsRepository(initial: StatisticsInput = StatisticsInput.EMPTY) : StatsRepository {
    val input = MutableStateFlow(initial)

    override fun observeInput(
        from: LocalDate,
        to: LocalDate,
        consistencyFrom: LocalDate,
        now: Instant,
    ): Flow<StatisticsInput> = input
}
