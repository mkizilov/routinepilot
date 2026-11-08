package com.routinepilot.data.execution

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ExecutionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

class DataStoreActiveExecutionStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStoreActiveExecutionStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(folder.root, "active-execution.preferences_pb") },
        )
        store = DataStoreActiveExecutionStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun save_thenObserve_returnsTheSameRun() = runBlocking {
        val run = sampleRun()

        store.save(run)

        assertEquals(run, store.observe().first())
    }

    @Test
    fun save_replacesThePreviousRun() = runBlocking {
        store.save(sampleRun())
        val updated = sampleRun().copy(stepIndex = 2, elapsedMillis = 42_000L, status = ExecutionStatus.PAUSED)

        store.save(updated)

        assertEquals(updated, store.observe().first())
    }

    @Test
    fun clear_removesTheRun() = runBlocking {
        store.save(sampleRun())

        store.clear()

        assertNull(store.observe().first())
    }

    @Test
    fun emptyStore_hasNoRun() = runBlocking {
        assertNull(store.observe().first())
    }

    @Test
    fun save_keepsTheCheckpointTimeThatWidgetsUse() = runBlocking {
        store.save(sampleRun().copy(savedAtEpochMillis = 1_234_567L))

        assertEquals(1_234_567L, store.observe().first()?.savedAtEpochMillis)
    }

    private fun sampleRun() = ActiveExecution(
        sessionId = UUID.fromString("11111111-2222-4333-8444-555555555555"),
        routineId = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa"),
        startedAt = Instant.parse("2026-03-01T09:00:00Z"),
        stepIds = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
        stepIndex = 1,
        elapsedMillis = 12_345L,
        status = ExecutionStatus.RUNNING,
    )
}
