package com.routinepilot.data.repository

import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.model.StepCompletionEvent
import com.routinepilot.domain.schedule.Recurrence
import com.routinepilot.domain.schedule.RecurrencePattern
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.database.TEST_TIME
import com.routinepilot.infrastructure.database.createInMemoryDatabase
import com.routinepilot.infrastructure.database.newRoutine
import com.routinepilot.infrastructure.database.newStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class ScheduleAndExecutionRepositoryTest {
    private lateinit var db: RoutinePilotDatabase
    private lateinit var routines: RoutineRepositoryImpl
    private lateinit var schedules: ScheduleRepositoryImpl
    private lateinit var execution: ExecutionRepositoryImpl

    @Before
    fun setUp() {
        db = createInMemoryDatabase()
        routines = RoutineRepositoryImpl(db)
        schedules = ScheduleRepositoryImpl(db)
        execution = ExecutionRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeOccurrences_ordersByScheduledTimeAndReflectsCompletion() = runBlocking {
        val routine = newRoutine()
        routines.saveRoutine(routine, emptyList())
        val now = Instant.parse("2026-03-10T12:00:00Z")
        schedules.saveSchedule(dailyRoutineSchedule(routine.id), now)
        val before = schedules.observeOccurrences(routine.id).first()
        val early = before.first()
        val late = before[1]

        schedules.completeOccurrence(late.id, now)

        val result = schedules.observeOccurrences(routine.id).first()
        assertEquals(before.map { it.id }, result.map { it.id })
        assertTrue(early.scheduledAt.isBefore(late.scheduledAt))
        assertEquals(OccurrenceStatus.PLANNED, result.first().status)
        assertEquals(OccurrenceStatus.COMPLETED, result[1].status)
    }

    @Test
    fun deleteOccurrence_hidesOnlyThatOccurrence() = runBlocking {
        val routine = newRoutine()
        routines.saveRoutine(routine, emptyList())
        val now = Instant.parse("2026-03-10T12:00:00Z")
        schedules.saveSchedule(dailyRoutineSchedule(routine.id), now)
        val rows = schedules.observeOccurrences(routine.id).first()

        schedules.deleteOccurrence(rows.first().id, now)

        assertEquals(rows.drop(1).map { it.id }, schedules.observeOccurrences(routine.id).first().map { it.id })
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

    private fun dailyRoutineSchedule(routineId: UUID) = Schedule(
        id = UUID.randomUUID(),
        target = ScheduleTarget.RoutineTarget(routineId),
        recurrence = Recurrence(RecurrencePattern.Daily, LocalTime.of(7, 0), LocalDate.of(2026, 3, 1)),
        zone = ZoneId.of("UTC"),
        createdAt = TEST_TIME,
        modifiedAt = TEST_TIME,
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
