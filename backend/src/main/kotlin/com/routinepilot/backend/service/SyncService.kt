package com.routinepilot.backend.service

import com.routinepilot.backend.db.Database
import com.routinepilot.backend.domain.ChangeInput
import com.routinepilot.backend.domain.ChangeOutcome
import com.routinepilot.backend.domain.ChangeRecord
import com.routinepilot.backend.domain.EntityHead
import com.routinepilot.backend.domain.EntityType
import com.routinepilot.backend.domain.NewChange
import com.routinepilot.backend.domain.Operation
import com.routinepilot.backend.domain.SyncDecider
import com.routinepilot.backend.persistence.SyncStore
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ChangePage(val changes: List<ChangeRecord>, val cursor: Long, val hasMore: Boolean)

data class RecordPage(val heads: List<EntityHead>, val next: UUID?)

/**
 * Versioned change sync. A device uploads changes that each name the version it last saw. The server applies a
 * change only when that version is still current, and otherwise reports a conflict with the current state. Devices
 * download the log from a cursor, so no device ever replaces the whole database.
 */
class SyncService(
    private val db: Database,
    private val store: SyncStore,
    private val clock: Clock,
) {
    suspend fun upload(userId: UUID, deviceId: UUID, changes: List<ChangeInput>): List<ChangeOutcome> {
        val now = clock.instant()
        return db.transaction { connection ->
            store.lockUser(connection, userId)
            changes.map { change -> applyChange(connection, userId, deviceId, change, now) }
        }
    }

    suspend fun download(userId: UUID, since: Long, limit: Int): ChangePage {
        val rows = db.transaction { store.changesSince(it, userId, since, limit + 1) }
        val page = rows.take(limit)
        return ChangePage(changes = page, cursor = page.lastOrNull()?.seq ?: since, hasMore = rows.size > limit)
    }

    suspend fun records(userId: UUID, type: EntityType, after: UUID, limit: Int): RecordPage {
        val rows = db.transaction { store.liveHeads(it, userId, type, after, limit + 1) }
        val page = rows.take(limit)
        return RecordPage(heads = page, next = if (rows.size > limit) page.last().entityId else null)
    }

    private fun applyChange(
        connection: Connection,
        userId: UUID,
        deviceId: UUID,
        change: ChangeInput,
        now: Instant,
    ): ChangeOutcome {
        val earlier = store.findChange(connection, userId, change.changeId)
        if (earlier != null) return replay(earlier, change)

        val head = store.findHead(connection, userId, change.entityType, change.entityId)
        return when (val decision = SyncDecider.decide(head?.version ?: 0L, change.baseVersion)) {
            is SyncDecider.Decision.Conflict -> ChangeOutcome.Conflict(
                changeId = change.changeId,
                entityType = change.entityType,
                entityId = change.entityId,
                serverVersion = decision.serverVersion,
                serverDeleted = head?.deleted ?: false,
                serverPayload = head?.payload,
            )
            is SyncDecider.Decision.Apply -> {
                val seq = store.appendChange(
                    connection,
                    userId,
                    NewChange(
                        changeId = change.changeId,
                        deviceId = deviceId,
                        entityType = change.entityType,
                        entityId = change.entityId,
                        operation = change.operation,
                        baseVersion = change.baseVersion,
                        version = decision.version,
                        payload = change.payload,
                        createdAt = now,
                    ),
                )
                store.upsertHead(
                    connection,
                    userId,
                    EntityHead(
                        entityType = change.entityType,
                        entityId = change.entityId,
                        version = decision.version,
                        deleted = change.operation == Operation.DELETE,
                        payload = change.payload,
                        lastChangeSeq = seq,
                        updatedAt = now,
                    ),
                )
                ChangeOutcome.Accepted(
                    change.changeId,
                    change.entityType,
                    change.entityId,
                    decision.version,
                    seq,
                    duplicate = false,
                )
            }
        }
    }

    /**
     * A repeated change ID returns the original result, so a retried upload never applies twice. Reusing the ID for a
     * different change is refused.
     */
    private fun replay(earlier: ChangeRecord, change: ChangeInput): ChangeOutcome {
        val same = earlier.entityType == change.entityType &&
            earlier.entityId == change.entityId &&
            earlier.operation == change.operation &&
            earlier.baseVersion == change.baseVersion &&
            earlier.payload == change.payload
        return if (same) {
            ChangeOutcome.Accepted(
                change.changeId,
                change.entityType,
                change.entityId,
                earlier.version,
                earlier.seq,
                duplicate = true,
            )
        } else {
            ChangeOutcome.Rejected(change.changeId, change.entityType, change.entityId, "change_id_reused")
        }
    }
}
