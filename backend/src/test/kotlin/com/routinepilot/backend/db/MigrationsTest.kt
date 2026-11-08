package com.routinepilot.backend.db

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrationsTest {
    private val dataSource by lazy { createDataSource(TestPostgres.databaseConfig) }

    @BeforeEach
    fun reset() {
        TestPostgres.reset()
    }

    @Test
    fun migrate_isIdempotent_andRecordsEachVersionOnce() {
        Migrations.migrate(dataSource)
        Migrations.migrate(dataSource)

        val versions = dataSource.connection.use { connection ->
            connection.query("SELECT version FROM schema_migrations ORDER BY version") { it.getInt("version") }
        }
        assertEquals(listOf(1), versions)
    }

    @Test
    fun schema_rejectsAnUpsertWithoutAPayload() {
        dataSource.connection.use { connection ->
            val userId = insertUserAndDevice(connection)
            val failure = assertFailsWith<SQLException> {
                connection.update(
                    "INSERT INTO sync_changes (user_id, change_id, device_id, entity_type, entity_id, operation, " +
                        "base_version, version, payload, created_at) " +
                        "VALUES (?, ?, ?, 'routine', ?, 'UPSERT', 0, 1, NULL, ?)",
                    userId,
                    UUID.randomUUID(),
                    deviceOf(connection, userId),
                    UUID.randomUUID(),
                    NOW,
                )
            }
            assertEquals(CHECK_VIOLATION, failure.sqlState)
            connection.rollback()
        }
    }

    @Test
    fun schema_rejectsATombstoneThatStillHasAPayload() {
        dataSource.connection.use { connection ->
            val userId = insertUserAndDevice(connection)
            val failure = assertFailsWith<SQLException> {
                connection.update(
                    "INSERT INTO sync_entities (user_id, entity_type, entity_id, version, deleted, payload, " +
                        "last_change_seq, updated_at) VALUES (?, 'task', ?, 1, TRUE, CAST('{}' AS jsonb), 1, ?)",
                    userId,
                    UUID.randomUUID(),
                    NOW,
                )
            }
            assertEquals(CHECK_VIOLATION, failure.sqlState)
            connection.rollback()
        }
    }

    private fun insertUserAndDevice(connection: java.sql.Connection): UUID {
        val userId = UUID.randomUUID()
        connection.update(
            "INSERT INTO users (id, email, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            userId,
            "${userId}@example.com",
            "hash",
            NOW,
            NOW,
        )
        connection.update(
            "INSERT INTO devices (id, user_id, name, platform, created_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            userId,
            "Pixel",
            "android",
            NOW,
        )
        connection.commit()
        return userId
    }

    private fun deviceOf(connection: java.sql.Connection, userId: UUID): UUID =
        connection.query("SELECT id FROM devices WHERE user_id = ?", userId) { it.uuid("id") }.single()

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-10T12:00:00Z")
        const val CHECK_VIOLATION = "23514"
    }
}
