package com.routinepilot.backend.persistence

import com.routinepilot.backend.db.instant
import com.routinepilot.backend.db.jsonObjectOrNull
import com.routinepilot.backend.db.query
import com.routinepilot.backend.db.queryOne
import com.routinepilot.backend.db.update
import com.routinepilot.backend.db.uuid
import com.routinepilot.backend.db.uuidOrNull
import com.routinepilot.backend.domain.ApiException
import com.routinepilot.backend.domain.ChangeRecord
import com.routinepilot.backend.domain.EntityHead
import com.routinepilot.backend.domain.EntityType
import com.routinepilot.backend.domain.NewChange
import com.routinepilot.backend.domain.Operation
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** SQL for the change log and the current record state. */
class SyncStore {
    /**
     * Takes a row lock on the user. Every upload for that user waits for the lock, so sequence numbers commit in order
     * and a download cursor never skips a change that another device is still writing.
     */
    fun lockUser(connection: Connection, userId: UUID) {
        val locked = connection.query("SELECT id FROM users WHERE id = ? FOR UPDATE", userId) { it.uuid("id") }
        if (locked.isEmpty()) throw ApiException(401, "unauthorized", "Sign in again.")
    }

    fun findHead(connection: Connection, userId: UUID, type: EntityType, entityId: UUID): EntityHead? =
        connection.queryOne(
            "$HEAD_SQL WHERE user_id = ? AND entity_type = ? AND entity_id = ?",
            userId,
            type.wireName,
            entityId,
        ) { readHead(it) }

    fun upsertHead(connection: Connection, userId: UUID, head: EntityHead) {
        connection.update(
            "INSERT INTO sync_entities " +
                "(user_id, entity_type, entity_id, version, deleted, payload, last_change_seq, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?) " +
                "ON CONFLICT (user_id, entity_type, entity_id) DO UPDATE SET " +
                "version = EXCLUDED.version, deleted = EXCLUDED.deleted, payload = EXCLUDED.payload, " +
                "last_change_seq = EXCLUDED.last_change_seq, updated_at = EXCLUDED.updated_at",
            userId,
            head.entityType.wireName,
            head.entityId,
            head.version,
            head.deleted,
            head.payload?.toString(),
            head.lastChangeSeq,
            head.updatedAt,
        )
    }

    /** Appends a change and returns its sequence number. */
    fun appendChange(connection: Connection, userId: UUID, change: NewChange): Long =
        connection.query(
            "INSERT INTO sync_changes (user_id, change_id, device_id, entity_type, entity_id, operation, " +
                "base_version, version, payload, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?) RETURNING seq",
            userId,
            change.changeId,
            change.deviceId,
            change.entityType.wireName,
            change.entityId,
            change.operation.name,
            change.baseVersion,
            change.version,
            change.payload?.toString(),
            change.createdAt,
        ) { it.getLong("seq") }.single()

    fun findChange(connection: Connection, userId: UUID, changeId: UUID): ChangeRecord? =
        connection.queryOne("$CHANGE_SQL WHERE user_id = ? AND change_id = ?", userId, changeId) { readChange(it) }

    fun changesSince(connection: Connection, userId: UUID, since: Long, limit: Int): List<ChangeRecord> =
        connection.query("$CHANGE_SQL WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?", userId, since, limit) {
            readChange(it)
        }

    /** Live records of one kind, ordered by id, so a client can page with the last id it saw. */
    fun liveHeads(connection: Connection, userId: UUID, type: EntityType, after: UUID, limit: Int): List<EntityHead> =
        connection.query(
            "$HEAD_SQL WHERE user_id = ? AND entity_type = ? AND deleted = FALSE AND entity_id > ? " +
                "ORDER BY entity_id LIMIT ?",
            userId,
            type.wireName,
            after,
            limit,
        ) { readHead(it) }

    private fun readHead(row: ResultSet) = EntityHead(
        entityType = wireType(row),
        entityId = row.uuid("entity_id"),
        version = row.getLong("version"),
        deleted = row.getBoolean("deleted"),
        payload = row.jsonObjectOrNull("payload"),
        lastChangeSeq = row.getLong("last_change_seq"),
        updatedAt = row.instant("updated_at"),
    )

    private fun readChange(row: ResultSet) = ChangeRecord(
        seq = row.getLong("seq"),
        changeId = row.uuid("change_id"),
        deviceId = row.uuidOrNull("device_id"),
        entityType = wireType(row),
        entityId = row.uuid("entity_id"),
        operation = Operation.valueOf(row.getString("operation")),
        baseVersion = row.getLong("base_version"),
        version = row.getLong("version"),
        payload = row.jsonObjectOrNull("payload"),
        createdAt = row.instant("created_at"),
    )

    private fun wireType(row: ResultSet): EntityType =
        checkNotNull(EntityType.fromWire(row.getString("entity_type"))) { "Unknown entity type in storage" }

    private companion object {
        const val HEAD_SQL =
            "SELECT entity_type, entity_id, version, deleted, payload::text AS payload, last_change_seq, updated_at " +
                "FROM sync_entities"
        const val CHANGE_SQL =
            "SELECT seq, change_id, device_id, entity_type, entity_id, operation, base_version, version, " +
                "payload::text AS payload, created_at FROM sync_changes"
    }
}
