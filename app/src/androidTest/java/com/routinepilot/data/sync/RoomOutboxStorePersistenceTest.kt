package com.routinepilot.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.routinepilot.domain.sync.BaseState
import com.routinepilot.domain.sync.Expectation
import com.routinepilot.domain.sync.FieldChange
import com.routinepilot.domain.sync.Mutation
import com.routinepilot.domain.sync.MutationKind
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.SyncType
import com.routinepilot.infrastructure.database.ForeignKeysCallback
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * The outbox is the device's only copy of unsent changes. It must survive the database being closed and opened again,
 * which is what a process restart does.
 */
class RoomOutboxStorePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = ObjectKey(SyncType.ROUTINE, UUID.fromString("00000000-0000-4000-8000-0000000000e1"))
    private val mutationId = UUID.fromString("00000000-0000-4000-8000-0000000000e2")

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun queuedChangesAndTheServerBase_surviveReopeningTheDatabase(): Unit =
        runBlocking {
            val first = open()
            RoomOutboxStore(first.syncOutboxDao()).also { store ->
                store.saveBase(key, BaseState(version = 3, deleted = false, fields = mapOf("title" to "Morning")))
                store.append(
                    Mutation(
                        mutationId = mutationId,
                        key = key,
                        localSequence = 0,
                        seenVersion = 3,
                        kind = MutationKind.UPSERT,
                        expectation = Expectation.PRESENT,
                        snapshot = mapOf("title" to "Evening"),
                        changes = mapOf("title" to FieldChange("Morning", "Evening")),
                        createdAtMillis = 1L,
                    ),
                )
            }
            first.close()

            val second = open()
            try {
                val store = RoomOutboxStore(second.syncOutboxDao())
                val pending = store.pending(key)

                assertEquals(listOf(mutationId), pending.map { it.mutationId })
                assertEquals(mapOf("title" to FieldChange("Morning", "Evening")), pending.single().changes)
                assertEquals(mapOf("title" to "Evening"), pending.single().snapshot)
                assertEquals(BaseState(3, false, mapOf("title" to "Morning")), store.base(key))
            } finally {
                second.close()
            }
        }

    private fun open(): RoutinePilotDatabase =
        Room
            .databaseBuilder(context, RoutinePilotDatabase::class.java, DB_NAME)
            .addCallback(ForeignKeysCallback)
            .build()

    private companion object {
        const val DB_NAME = "outbox-persistence-test.db"
    }
}
