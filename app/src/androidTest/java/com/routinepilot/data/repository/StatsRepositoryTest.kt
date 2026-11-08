package com.routinepilot.data.repository

import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.SessionOutcome
import com.routinepilot.domain.statistics.OccurrenceTotals
import com.routinepilot.domain.statistics.RoutineTotals
import com.routinepilot.domain.statistics.SessionTotals
import com.routinepilot.domain.statistics.StatisticsInput
import com.routinepilot.infrastructure.database.ExecutionSessionEntity
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.database.ScheduledOccurrenceEntity
import com.routinepilot.infrastructure.database.StepCompletionEventEntity
import com.routinepilot.infrastructure.database.TEST_TIME
import com.routinepilot.infrastructure.database.createInMemoryDatabase
import com.routinepilot.infrastructure.database.newRoutine
import com.routinepilot.infrastructure.database.newStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class StatsRepositoryTest {
    private lateinit var db: RoutinePilotDatabase
    private lateinit var routines: RoutineRepositoryImpl
    private lateinit var executions: ExecutionRepositoryImpl
    private lateinit var stats: StatsRepositoryImpl
    private val routine = newRoutine(title = "Morning")
    private val step = newStep(routine.id, "Brush", 0)
    private val day = LocalDate.of(2026, 3, 9)
    private val nextDay = LocalDate.of(2026, 3, 10)
    private val now = Instant.parse("2026-03-10T12:00:00Z")
    private val consistencyFrom = LocalDate.of(2026, 2, 10)

    @Before
    fun setUp() {
        db = createInMemoryDatabase()
        routines = RoutineRepositoryImpl(db)
        executions = ExecutionRepositoryImpl(db)
        stats = StatsRepositoryImpl(db)
        runBlocking { routines.saveRoutine(routine, listOf(step)) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeInput_aggregatesItemsSessionsAndStepsPerDay_andIgnoresDeletedAndUnrecordedRows() =
        runBlocking {
            insertOccurrence(Instant.parse("2026-03-09T07:00:00Z"), day, OccurrenceStatus.COMPLETED)
            insertOccurrence(Instant.parse("2026-03-09T09:00:00Z"), day, OccurrenceStatus.SKIPPED)
            insertOccurrence(Instant.parse("2026-03-09T08:00:00Z"), day, OccurrenceStatus.COMPLETED, deletedAt = now)
            insertOccurrence(Instant.parse("2026-03-10T07:00:00Z"), nextDay, OccurrenceStatus.PLANNED)
            insertOccurrence(Instant.parse("2026-03-10T18:00:00Z"), nextDay, OccurrenceStatus.PLANNED)

            insertSession(day, SessionOutcome.FINISHED, focusedMillis = 600_000L)
            insertSession(day, SessionOutcome.CANCELLED, focusedMillis = 120_000L)
            insertSession(day, SessionOutcome.UNRECORDED, focusedMillis = null)
            insertSession(day, outcome = null, focusedMillis = null, endedAt = null)

            insertStepCompletion(day)
            insertStepCompletion(day)

            val input = input()

            assertEquals(OccurrenceTotals(completed = 1, skipped = 1, missed = 0), input.occurrences.getValue(day))
            assertEquals(OccurrenceTotals(completed = 0, skipped = 0, missed = 1), input.occurrences.getValue(nextDay))
            assertEquals(
                SessionTotals(finished = 1, cancelled = 1, focusedMillis = 720_000L),
                input.sessions.getValue(day),
            )
            assertEquals(2, input.steps.getValue(day))
            assertEquals(listOf(RoutineTotals(routine.id, completed = 1, missed = 1)), input.routines)
        }

    @Test
    fun startSession_linksTheEarliestItemDueWithinAnHour_andFinishingCompletesIt() =
        runBlocking {
            val due = insertOccurrence(Instant.parse("2026-03-10T07:00:00Z"), nextDay, OccurrenceStatus.PLANNED)

            val started =
                executions.startSession(
                    sessionFor(startedAt = Instant.parse("2026-03-10T06:30:00Z"), localDate = nextDay),
                )
            assertEquals(due.id, started.occurrenceId)

            executions.endSession(
                started.id,
                SessionOutcome.FINISHED,
                Instant.parse("2026-03-10T07:20:00Z"),
                1_200_000L,
            )

            assertEquals(OccurrenceStatus.COMPLETED, db.scheduledOccurrenceDao().findById(due.id)!!.status)
        }

    @Test
    fun cancelledSession_leavesItsLinkedItemPending() =
        runBlocking {
            val due = insertOccurrence(Instant.parse("2026-03-10T07:00:00Z"), nextDay, OccurrenceStatus.PLANNED)
            val started =
                executions.startSession(
                    sessionFor(startedAt = Instant.parse("2026-03-10T06:30:00Z"), localDate = nextDay),
                )

            executions.endSession(started.id, SessionOutcome.CANCELLED, Instant.parse("2026-03-10T06:40:00Z"), 60_000L)

            assertEquals(OccurrenceStatus.PLANNED, db.scheduledOccurrenceDao().findById(due.id)!!.status)
        }

    @Test
    fun startSession_doesNotLinkItemsDueMoreThanAnHourAhead() =
        runBlocking {
            insertOccurrence(Instant.parse("2026-03-10T09:00:00Z"), nextDay, OccurrenceStatus.PLANNED)

            val started =
                executions.startSession(
                    sessionFor(startedAt = Instant.parse("2026-03-10T06:30:00Z"), localDate = nextDay),
                )

            assertNull(started.occurrenceId)
        }

    @Test
    fun endSession_onlyTakesEffectOnce() =
        runBlocking {
            val started =
                executions.startSession(
                    sessionFor(startedAt = Instant.parse("2026-03-10T06:30:00Z"), localDate = nextDay),
                )

            executions.endSession(started.id, SessionOutcome.FINISHED, Instant.parse("2026-03-10T06:40:00Z"), 600_000L)
            executions.endSession(started.id, SessionOutcome.CANCELLED, Instant.parse("2026-03-10T06:50:00Z"), 0L)

            val row = db.executionSessionDao().findById(started.id)!!
            assertEquals(SessionOutcome.FINISHED, row.outcome)
            assertEquals(600_000L, row.focusedMillis)
        }

    @Test
    fun observeInput_emitsAgainWhenASessionFinishes() =
        runBlocking {
            val seen = Channel<StatisticsInput>(Channel.UNLIMITED)
            val collector =
                launch(Dispatchers.IO) {
                    stats.observeInput(day, nextDay, consistencyFrom, now).collect { seen.send(it) }
                }
            try {
                seen.receive()
                val started =
                    executions.startSession(
                        sessionFor(startedAt = Instant.parse("2026-03-09T06:00:00Z"), localDate = day),
                    )
                executions.endSession(
                    started.id,
                    SessionOutcome.FINISHED,
                    Instant.parse("2026-03-09T06:30:00Z"),
                    1_800_000L,
                )

                withTimeout(TIMEOUT_MILLIS) {
                    var latest = seen.receive()
                    while (latest.sessions[day]?.finished != 1) latest = seen.receive()
                    assertEquals(1_800_000L, latest.sessions.getValue(day).focusedMillis)
                }
            } finally {
                collector.cancel()
            }
        }

    private suspend fun input(): StatisticsInput = stats.observeInput(day, nextDay, consistencyFrom, now).first()

    private fun insertOccurrence(
        at: Instant,
        localDate: LocalDate,
        status: OccurrenceStatus,
        deletedAt: Instant? = null,
    ): ScheduledOccurrenceEntity {
        val row =
            ScheduledOccurrenceEntity(
                id = UUID.randomUUID(),
                scheduleId = null,
                routineId = routine.id,
                taskId = null,
                originalScheduledAt = at,
                scheduledAt = at,
                localDate = localDate,
                status = status,
                createdAt = TEST_TIME,
                modifiedAt = TEST_TIME,
                deletedAt = deletedAt,
            )
        runBlocking { db.scheduledOccurrenceDao().insertIgnoring(listOf(row)) }
        return row
    }

    private fun insertSession(
        localDate: LocalDate,
        outcome: SessionOutcome?,
        focusedMillis: Long?,
        endedAt: Instant? = localDate.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(900),
    ) = runBlocking {
        val started = localDate.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(60)
        db.executionSessionDao().upsert(
            ExecutionSessionEntity(
                id = UUID.randomUUID(),
                routineId = routine.id,
                occurrenceId = null,
                startedAt = started,
                endedAt = endedAt,
                createdAt = started,
                modifiedAt = started,
                outcome = outcome,
                focusedMillis = focusedMillis,
                localDate = localDate,
            ),
        )
    }

    private fun insertStepCompletion(localDate: LocalDate) =
        runBlocking {
            val sessionId = UUID.randomUUID()
            val started = localDate.atStartOfDay().toInstant(ZoneOffset.UTC)
            db.executionSessionDao().upsert(
                ExecutionSessionEntity(
                    id = sessionId,
                    routineId = routine.id,
                    occurrenceId = null,
                    startedAt = started,
                    endedAt = null,
                    createdAt = started,
                    modifiedAt = started,
                    localDate = localDate,
                ),
            )
            db.stepCompletionEventDao().upsert(
                StepCompletionEventEntity(
                    id = UUID.randomUUID(),
                    sessionId = sessionId,
                    stepId = step.id,
                    completedAt = started.plusSeconds(120),
                    createdAt = started,
                    modifiedAt = started,
                    localDate = localDate,
                ),
            )
        }

    private fun sessionFor(
        startedAt: Instant,
        localDate: LocalDate,
    ) = ExecutionSession(
        id = UUID.randomUUID(),
        routineId = routine.id,
        occurrenceId = null,
        startedAt = startedAt,
        endedAt = null,
        createdAt = startedAt,
        localDate = localDate,
    )

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
