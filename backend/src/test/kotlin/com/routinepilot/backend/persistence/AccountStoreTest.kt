package com.routinepilot.backend.persistence

import com.routinepilot.backend.db.TestPostgres
import com.routinepilot.backend.db.createDataSource
import com.routinepilot.backend.db.query
import com.routinepilot.backend.db.update
import com.routinepilot.backend.domain.Device
import com.routinepilot.backend.domain.NewSession
import com.routinepilot.backend.domain.UserAccount
import com.routinepilot.backend.security.SessionTokens
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AccountStoreTest {
    private val dataSource by lazy { createDataSource(TestPostgres.databaseConfig) }
    private val store = AccountStore()

    @BeforeEach
    fun reset() {
        TestPostgres.reset()
    }

    @Test
    fun deletingAnAccount_removesItsDevicesSessionsAndSyncedData() {
        inTransaction { connection ->
            val user = UserAccount(UUID.randomUUID(), "gone@example.com", "hash", NOW)
            store.insertUser(connection, user)
            val device = Device(UUID.randomUUID(), user.id, "Pixel", "android", NOW, revokedAt = null)
            store.insertDevice(connection, device)
            store.insertSession(connection, session(user.id, device.id, "token-a"))
            connection.update(
                "INSERT INTO sync_entities (user_id, entity_type, entity_id, version, deleted, payload, " +
                    "last_change_seq, updated_at) VALUES (?, 'task', ?, 1, FALSE, CAST('{}' AS jsonb), 1, ?)",
                user.id,
                UUID.randomUUID(),
                NOW,
            )

            store.deleteUser(connection, user.id)

            listOf("users", "devices", "sessions", "sync_entities").forEach { table ->
                assertEquals(0L, countRows(connection, table, user.id), table)
            }
        }
    }

    @Test
    fun activeSession_ignoresExpiredRevokedAndRevokedDeviceSessions() {
        inTransaction { connection ->
            val user = UserAccount(UUID.randomUUID(), "active@example.com", "hash", NOW)
            store.insertUser(connection, user)
            val device = Device(UUID.randomUUID(), user.id, "Pixel", "android", NOW, revokedAt = null)
            store.insertDevice(connection, device)
            val live = session(user.id, null, "live")
            store.insertSession(connection, live)
            store.insertSession(connection, session(user.id, null, "expired", expiresAt = NOW.minusSeconds(1)))
            store.insertSession(connection, session(user.id, null, "revoked"))
            store.revokeSession(connection, sessionIdFor(connection, "revoked"), NOW)
            store.insertSession(connection, session(user.id, device.id, "device"))
            store.revokeDevice(connection, device.id, NOW)

            assertNotNull(store.findActiveSession(connection, live.tokenHash, NOW))
            assertNull(store.findActiveSession(connection, hashOf("expired"), NOW))
            assertNull(store.findActiveSession(connection, hashOf("revoked"), NOW))
            assertNull(store.findActiveSession(connection, hashOf("device"), NOW))
        }
    }

    private fun session(
        userId: UUID,
        deviceId: UUID?,
        label: String,
        expiresAt: Instant = NOW.plusSeconds(3_600),
    ) = NewSession(
        id = UUID.randomUUID(),
        userId = userId,
        deviceId = deviceId,
        tokenHash = hashOf(label),
        createdAt = NOW,
        expiresAt = expiresAt,
    )

    private fun hashOf(label: String): String = SessionTokens.hash("token:$label")

    private fun sessionIdFor(connection: Connection, label: String): UUID =
        connection.query("SELECT id FROM sessions WHERE token_hash = ?", hashOf(label)) { it.getString("id") }
            .single()
            .let(UUID::fromString)

    private fun countRows(connection: Connection, table: String, userId: UUID): Long {
        val column = if (table == "users") "id" else "user_id"
        return connection
            .query("SELECT COUNT(*) AS n FROM $table WHERE $column = ?", userId) { it.getLong("n") }
            .single()
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

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-10T12:00:00Z")
    }
}
