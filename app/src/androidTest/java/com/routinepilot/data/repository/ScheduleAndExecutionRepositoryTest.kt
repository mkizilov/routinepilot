package com.routinepilot.data.repository

import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.ScheduledOccurrence
import com.routinepilot.domain.model.StepCompletionEvent
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.database.TEST_TIME
import com.routinepilot.infrastructure.database.createInMemoryDatabase
import com.routinepilot.infrastructure.database.newRoutine
import com.routinepilot.infrastructure.database.newStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ScheduleAndExecutionRepositoryTest {
    private lateinit var db: RoutinePilotDatabase
    private lateinit var routines: RoutineRepositoryImpl
    private lateinit var schedule: ScheduleRepositoryImpl
    private lateinit var execution: ExecutionRepositoryImpl

    @Before
    fun setUp() {
        db = createInMemoryDatabase()
        routines = RoutineRepositoryImpl(db)
        schedule = ScheduleRepositoryImpl(db)
        execution = ExecutionRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeOccurrences_ordersByScheduledTimeAndUpdatesStatus() = runBlocking {
        val routine = newRoutine()
        routines.saveRoutine(routine, emptyList())
        val late = occurrence(routine.id, Instant.parse("2026-02-02T08:00:00Z"))
        val early = occurrence(routine.id, Instant.parse("2026-02-01T08:00:00Z"))
        schedule.saveOccurrence(late)
        schedule.saveOccurrence(early)

        schedule.saveOccurrence(early.copy(status = OccurrenceStatus.COMPLETED))

        val result = schedule.observeOccurrences(routine.id).first()
        assertEquals(listOf(early.id, late.id), result.map { it.id })
        assertEquals(OccurrenceStatus.COMPLETED, result.first().status)
    }

    @Test
    fun deleteOccurrence_hidesOccurrence() = runBlocking {
        val routine = newRoutine()
        routines.saveRoutine(routine, emptyList())
        val occurrence = occurrence(routine.id, TEST_TIME)
        schedule.saveOccurrence(occurrence)

        schedule.deleteOccurrence(occurrence.id)

        assertEquals(emptyList<ScheduledOccurrence>(), schedule.observeOccurrences(routine.id).first())
    }

    @Test
    fun observeSessions_returnsMostRecentlyStartedFirst() = runBlocking {
        val routine = newRoutine()
        routines.saveRoutine(routine, emptyList())
        val older = session(routine.id, Instant.parse("2026-03-01T07:00:00Z"))
        val newer = session(routine.id, Instant.parse("2026-03-02T07:00:00Z"))
        execution.saveSession(older)
        execution.saveSession(newer)

        execution.saveSession(older.copy(endedAt = Instant.parse("2026-03-01T07:30:00Z")))

        val sessions = execution.observeSessions(routine.id).first()
        assertEquals(listOf(newer.id, older.id), sessions.map { it.id })
        assertEquals(Instant.parse("2026-03-01T07:30:00Z"), sessions.last().endedAt)
    }

    @Test
    fun observeCompletions_ordersByCompletionTime() = runBlocking {
        val routine = newRoutine()
        val steps = listOf(newStep(routine.id, "Brush", 0), newStep(routine.id, "Floss", 1))
        routines.saveRoutine(routine, steps)
        val sessionRecord = session(routine.id, TEST_TIME)
        execution.saveSession(sessionRecord)
        val flossed = event(sessionRecord.id, steps[1].id, Instant.parse("2026-01-01T00:05:00Z"))
        val brushed = event(sessionRecord.id, steps[0].id, Instant.parse("2026-01-01T00:01:00Z"))
        execution.recordStepCompletion(flossed)
        execution.recordStepCompletion(brushed)

        assertEquals(
            listOf(brushed.id, flossed.id),
            execution.observeCompletions(sessionRecord.id).first().map { it.id },
        )
    }

    @Test
    fun deleteSession_hidesSession() = runBlocking {
        val routine = newRoutine()
        routines.saveRoutine(routine, emptyList())
        val sessionRecord = session(routine.id, TEST_TIME)
        execution.saveSession(sessionRecord)

        execution.deleteSession(sessionRecord.id)

        assertEquals(emptyList<ExecutionSession>(), execution.observeSessions(routine.id).first())
    }

    private fun occurrence(routineId: UUID, scheduledAt: Instant) = ScheduledOccurrence(
        id = UUID.randomUUID(),
        routineId = routineId,
        scheduledAt = scheduledAt,
        status = OccurrenceStatus.PLANNED,
        createdAt = TEST_TIME,
    )

    private fun session(routineId: UUID, startedAt: Instant) = ExecutionSession(
        id = UUID.randomUUID(),
        routineId = routineId,
        occurrenceId = null,
        startedAt = startedAt,
        endedAt = null,
        createdAt = TEST_TIME,
    )

    private fun event(sessionId: UUID, stepId: UUID, completedAt: Instant) = StepCompletionEvent(
        id = UUID.randomUUID(),
        sessionId = sessionId,
        stepId = stepId,
        completedAt = completedAt,
        createdAt = TEST_TIME,
    )
}
