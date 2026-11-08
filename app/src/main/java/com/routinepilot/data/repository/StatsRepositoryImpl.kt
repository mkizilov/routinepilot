package com.routinepilot.data.repository

import com.routinepilot.domain.repository.StatsRepository
import com.routinepilot.domain.statistics.OccurrenceTotals
import com.routinepilot.domain.statistics.RoutineTotals
import com.routinepilot.domain.statistics.SessionTotals
import com.routinepilot.domain.statistics.StatisticsInput
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

class StatsRepositoryImpl @Inject constructor(
    database: RoutinePilotDatabase,
) : StatsRepository {
    private val statsDao = database.statsDao()

    override fun observeInput(
        from: LocalDate,
        to: LocalDate,
        consistencyFrom: LocalDate,
        now: Instant,
    ): Flow<StatisticsInput> {
        val fromDay = from.toString()
        val toDay = to.toString()
        val nowMillis = now.toEpochMilli()
        return combine(
            statsDao.occurrenceDays(fromDay, toDay, nowMillis),
            statsDao.sessionDays(fromDay, toDay),
            statsDao.stepDays(fromDay, toDay),
            statsDao.routineTotals(consistencyFrom.toString(), toDay, nowMillis),
        ) { occurrences, sessions, steps, routines ->
            StatisticsInput(
                occurrences = occurrences.associate {
                    LocalDate.parse(it.day) to OccurrenceTotals(it.completed, it.skipped, it.missed)
                },
                sessions = sessions.associate {
                    LocalDate.parse(it.day) to SessionTotals(it.finished, it.cancelled, it.focusedMillis)
                },
                steps = steps.associate { LocalDate.parse(it.day) to it.steps },
                routines = routines.map { RoutineTotals(UUID.fromString(it.routineId), it.completed, it.missed) },
            )
        }
    }
}
