package com.routinepilot.infrastructure.database

import android.database.sqlite.SQLiteConstraintException
import com.routinepilot.data.repository.ExecutionRepositoryImpl
import com.routinepilot.data.repository.RoutineRepositoryImpl
import com.routinepilot.data.repository.TaskRepositoryImpl
import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.StepCompletionEvent
import com.routinepilot.domain.model.Task
import com.routinepilot.domain.schedule.RecurrenceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ReferentialIntegrityTest {
    private lateinit var db: RoutinePilotDatabase
    private lateinit var routines: RoutineRepositoryImpl
    private lateinit var tasks: TaskRepositoryImpl
    private lateinit var execution: ExecutionRepositoryImpl

    @Before
    fun setUp() {
        db = createInMemoryDatabase()
        routines = RoutineRepositoryImpl(db)
        tasks = TaskRepositoryImpl(db)
        execution = ExecutionRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun occurrenceForUnknownRoutine_isRejected() {
        val orphan = occurrence(routineId = UUID.randomUUID())

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.scheduledOccurrenceDao().insertIgnoring(listOf(orphan)) }
        }
    }

    @Test
    fun occurrenceForUnknownTask_isRejected() {
        val orphan = occurrence(routineId = null, taskId = UUID.randomUUID())

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.scheduledOccurrenceDao().insertIgnoring(listOf(orphan)) }
        }
    }

    @Test
    fun sessionForUnknownRoutine_isRejected() {
        val orphan = ExecutionSession(
            id = UUID.randomUUID(),
            routineId = UUID.randomUUID(),
            occurrenceId = null,
            startedAt = TEST_TIME,
            endedAt = null,
            createdAt = TEST_TIME,
        )

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { execution.saveSession(orphan) }
        }
    }

    @Test
    fun completionEventForUnknownStep_isRejected() = runBlocking {
        val routine = newRoutine()
        routines.saveRoutine(routine, emptyList())
        val sessionRecord = ExecutionSession(
            id = UUID.randomUUID(),
            routineId = routine.id,
            occurrenceId = null,
            startedAt = TEST_TIME,
            endedAt = null,
            createdAt = TEST_TIME,
        )
        execution.saveSession(sessionRecord)
        val event = StepCompletionEvent(
            id = UUID.randomUUID(),
            sessionId = sessionRecord.id,
            stepId = UUID.randomUUID(),
            completedAt = TEST_TIME,
            createdAt = TEST_TIME,
        )

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { execution.recordStepCompletion(event) }
        }
    }

    @Test
    fun hardPurgeOfRoutine_cascadesToStepsOccurrencesAndSessions() = runBlocking {
        val routine = newRoutine()
        routines.saveRoutine(routine, listOf(newStep(routine.id, "Brush", 0)))
        db.scheduledOccurrenceDao().insertIgnoring(listOf(occurrence(routineId = routine.id)))
        execution.saveSession(
            ExecutionSession(UUID.randomUUID(), routine.id, null, TEST_TIME, null, TEST_TIME),
        )

        db.routineDao().purge(routine.id)

        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM routine_steps"))
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM scheduled_occurrences"))
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM execution_sessions"))
    }

    @Test
    fun hardPurgeOfRoutine_detachesLinkedTasks() = runBlocking {
        val routine = newRoutine()
        routines.saveRoutine(routine, emptyList())
        tasks.saveTask(
            Task(UUID.randomUUID(), "Linked", false, routine.id, 0, TEST_TIME),
        )

        db.routineDao().purge(routine.id)

        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM tasks WHERE routineId IS NULL"))
    }

    @Test
    fun hardPurgeOfSchedule_cascadesToItsGeneratedOccurrences() = runBlocking {
        val routine = newRoutine()
        routines.saveRoutine(routine, emptyList())
        val scheduleId = UUID.randomUUID()
        db.scheduleDao().upsert(
            ScheduleEntity(
                id = scheduleId,
                routineId = routine.id,
                taskId = null,
                kind = RecurrenceKind.DAILY,
                weekdaysMask = null,
                intervalDays = null,
                timeOfDayMinutes = 420,
                startDate = LocalDate.of(2026, 3, 1),
                zoneId = ZoneId.of("UTC"),
                createdAt = TEST_TIME,
                modifiedAt = TEST_TIME,
            ),
        )
        db.scheduledOccurrenceDao().insertIgnoring(listOf(occurrence(routine.id, scheduleId = scheduleId)))

        db.openHelper.writableDatabase.execSQL("DELETE FROM schedules WHERE id = ?", arrayOf(scheduleId.toString()))

        assertEquals(
            0L,
            db.scalar("SELECT COUNT(*) FROM scheduled_occurrences WHERE scheduleId = ?", scheduleId.toString()),
        )
    }

    @Test
    fun stepWithRecordedCompletion_cannotBeHardDeleted() = runBlocking {
        val routine = newRoutine()
        val step = newStep(routine.id, "Brush", 0)
        routines.saveRoutine(routine, listOf(step))
        val sessionRecord = ExecutionSession(UUID.randomUUID(), routine.id, null, TEST_TIME, null, TEST_TIME)
        execution.saveSession(sessionRecord)
        execution.recordStepCompletion(
            StepCompletionEvent(UUID.randomUUID(), sessionRecord.id, step.id, TEST_TIME, TEST_TIME),
        )

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.routineStepDao().purge(step.id) }
        }
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM routine_steps"))
    }

    private fun occurrence(
        routineId: UUID?,
        taskId: UUID? = null,
        scheduleId: UUID? = null,
    ) = ScheduledOccurrenceEntity(
        id = UUID.randomUUID(),
        scheduleId = scheduleId,
        routineId = routineId,
        taskId = taskId,
        originalScheduledAt = TEST_TIME,
        scheduledAt = TEST_TIME,
        localDate = LocalDate.of(2026, 1, 1),
        status = OccurrenceStatus.PLANNED,
        createdAt = TEST_TIME,
        modifiedAt = TEST_TIME,
    )
}
