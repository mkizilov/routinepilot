package com.routinepilot.data.execution

import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.model.Routine
import com.routinepilot.testing.FakeActiveExecutionStore
import com.routinepilot.testing.FakeRoutineRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

class RunRecoveryTest {
    private val routine = Routine(UUID.randomUUID(), "Morning", 0, Instant.EPOCH)
    private val routines = FakeRoutineRepository(listOf(routine))
    private val sessionId = UUID.randomUUID()

    @Test
    fun pauseInterruptedRun_pausesARunningRunAtItsCheckpoint_andReturnsIt() = runBlocking {
        val store = FakeActiveExecutionStore(run(ExecutionStatus.RUNNING))
        val recovery = RunRecovery(store, routines)

        val interrupted = recovery.pauseInterruptedRun()

        assertEquals(InterruptedRun(sessionId, routine.id, "Morning"), interrupted)
        val saved = store.current.value!!
        assertEquals(ExecutionStatus.PAUSED, saved.status)
        assertEquals(42_000L, saved.elapsedMillis)
    }

    @Test
    fun pauseInterruptedRun_leavesPausedAndEmptyStoresAlone() = runBlocking {
        val paused = run(ExecutionStatus.PAUSED)
        val store = FakeActiveExecutionStore(paused)
        val recovery = RunRecovery(store, routines)

        assertNull(recovery.pauseInterruptedRun())
        assertEquals(paused, store.current.value)

        val empty = FakeActiveExecutionStore()
        assertNull(RunRecovery(empty, routines).pauseInterruptedRun())
    }

    @Test
    fun pausedRunForReminder_matchesOnlyTheSamePausedSession() = runBlocking {
        val store = FakeActiveExecutionStore(run(ExecutionStatus.PAUSED))
        val recovery = RunRecovery(store, routines)

        assertEquals(InterruptedRun(sessionId, routine.id, "Morning"), recovery.pausedRunForReminder(sessionId))
        assertNull(recovery.pausedRunForReminder(UUID.randomUUID()))
    }

    @Test
    fun pausedRunForReminder_isSkippedOnceTheRunIsRunningAgain() = runBlocking {
        val store = FakeActiveExecutionStore(run(ExecutionStatus.RUNNING))

        assertNull(RunRecovery(store, routines).pausedRunForReminder(sessionId))
    }

    private fun run(status: ExecutionStatus) = ActiveExecution(
        sessionId = sessionId,
        routineId = routine.id,
        startedAt = Instant.EPOCH,
        stepIds = listOf(UUID.randomUUID()),
        stepIndex = 0,
        elapsedMillis = 42_000L,
        status = status,
    )
}
