package com.routinepilot.backend.persistence

import com.routinepilot.backend.db.TestPostgres
import com.routinepilot.backend.db.createDataSource
import com.routinepilot.backend.domain.ApiException
import com.routinepilot.backend.domain.Device
import com.routinepilot.backend.domain.EntityHead
import com.routinepilot.backend.domain.EntityType
import com.routinepilot.backend.domain.NewChange
import com.routinepilot.backend.domain.Operation
import com.routinepilot.backend.domain.UserAccount
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Each test runs in a transaction that is rolled back, so the tests do not depend on one another. */
class SyncStoreTest {
    private val dataSource by lazy { createDataSource(TestPostgres.databaseConfig) }
    private val accounts = AccountStore()
    private val sync = SyncStore()

    @BeforeEach
    fun reset() {
        TestPostgres.reset()
    }

    @Test
    fun sequenceNumbers_increaseInCommitOrder() {
        inTransaction { connection ->
            val user = createUser(connection, "one@example.com")
            val device = createDevice(connection, user)
            val first = sync.appendChange(connection, user, change(device, UUID.randomUUID()))
            val second = sync.appendChange(connection, user, change(device, UUID.randomUUID()))
            assertTrue(second > first)
        }
    }

    @Test
    fun changeIds_areUniquePerUser() {
        inTransaction { connection ->
            val user = createUser(connection, "one@example.com")
            val device = createDevice(connection, user)
            val repeated = change(device, UUID.randomUUID())
            sync.appendChange(connection, user, repeated)
            val failure = assertFailsWith<SQLException> { sync.appendChange(connection, user, repeated) }
            assertEquals(UNIQUE_VIOLATION, failure.sqlState)
        }
    }

    @Test
    fun sameChangeId_isAllowedForAnotherUser() {
        inTransaction { connection ->
            val first = createUser(connection, "one@example.com")
            val second = createUser(connection, "two@example.com")
            val changeId = UUID.randomUUID()
            sync.appendChange(connection, first, change(createDevice(connection, first), UUID.randomUUID(), changeId))
            sync.appendChange(connection, second, change(createDevice(connection, second), UUID.randomUUID(), changeId))
        }
    }

    @Test
    fun heads_roundTripTheirStateAndPayload() {
        inTransaction { connection ->
            val user = createUser(connection, "one@example.com")
            val entityId = UUID.randomUUID()
            sync.upsertHead(connection, user, head(entityId, version = 2, title = "Evening"))

            val found = sync.findHead(connection, user, EntityType.TASK, entityId)

            assertEquals(2L, found?.version)
            assertEquals(false, found?.deleted)
            assertEquals("Evening", found?.payload?.get("title")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun liveHeads_hideTombstones_andPageByEntityId() {
        inTransaction { connection ->
            val user = createUser(connection, "one@example.com")
            // PostgreSQL orders uuid values byte by byte, which matches sorting their lowercase text form.
            val ids = List(3) { UUID.randomUUID() }.sortedBy { it.toString() }
            ids.forEach { sync.upsertHead(connection, user, head(it, version = 1, title = "t")) }
            sync.upsertHead(connection, user, tombstone(ids[1]))

            val firstPage = sync.liveHeads(connection, user, EntityType.TASK, NIL, limit = 10)
            val afterFirst = sync.liveHeads(connection, user, EntityType.TASK, ids[0], limit = 10)

            assertEquals(listOf(ids[0], ids[2]), firstPage.map { it.entityId })
            assertEquals(listOf(ids[2]), afterFirst.map { it.entityId })
        }
    }

    @Test
    fun changesSince_returnsOnlyThisUsersLaterChanges() {
        inTransaction { connection ->
            val user = createUser(connection, "one@example.com")
            val other = createUser(connection, "two@example.com")
            val device = createDevice(connection, user)
            val otherDevice = createDevice(connection, other)
            val first = sync.appendChange(connection, user, change(device, UUID.randomUUID()))
            sync.appendChange(connection, user, change(device, UUID.randomUUID()))
            sync.appendChange(connection, other, change(otherDevice, UUID.randomUUID()))
            sync.appendChange(connection, user, change(device, UUID.randomUUID()))

            val rows = sync.changesSince(connection, user, since = first, limit = 10)

            assertEquals(2, rows.size)
            assertTrue(rows.all { it.deviceId == device && it.seq > first })
        }
    }

    @Test
    fun lockUser_refusesAnUnknownUser() {
        inTransaction { connection ->
            val failure = assertFailsWith<ApiException> { sync.lockUser(connection, UUID.randomUUID()) }
            assertEquals(401, failure.status)
        }
    }

    private fun inTransaction(block: (Connection) -> Unit) {
        dataSource.connection.use { connection ->
            try {
                block(connection)
            } finally {
                connection.rollback()
            }
        }
    }

    private fun createUser(connection: Connection, email: String): UUID {
        val user = UserAccount(UUID.randomUUID(), email, "hash", NOW)
        accounts.insertUser(connection, user)
        return user.id
    }

    private fun createDevice(connection: Connection, userId: UUID): UUID {
        val device = Device(UUID.randomUUID(), userId, "Pixel", "android", NOW, revokedAt = null)
        accounts.insertDevice(connection, device)
        return device.id
    }

    private fun change(deviceId: UUID, entityId: UUID, changeId: UUID = UUID.randomUUID()) = NewChange(
        changeId = changeId,
        deviceId = deviceId,
        entityType = EntityType.ROUTINE,
        entityId = entityId,
        operation = Operation.UPSERT,
        baseVersion = 0,
        version = 1,
        payload = buildJsonObject { put("title", "Morning") },
        createdAt = NOW,
    )

    private fun head(entityId: UUID, version: Long, title: String) = EntityHead(
        entityType = EntityType.TASK,
        entityId = entityId,
        version = version,
        deleted = false,
        payload = buildJsonObject { put("title", title) },
        lastChangeSeq = 1,
        updatedAt = NOW,
    )

    private fun tombstone(entityId: UUID) = EntityHead(
        entityType = EntityType.TASK,
        entityId = entityId,
        version = 2,
        deleted = true,
        payload = null,
        lastChangeSeq = 2,
        updatedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-10T12:00:00Z")
        val NIL: UUID = UUID(0L, 0L)
        const val UNIQUE_VIOLATION = "23505"
    }
}
