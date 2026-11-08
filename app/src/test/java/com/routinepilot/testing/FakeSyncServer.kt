package com.routinepilot.testing

import com.routinepilot.domain.sync.BaseState
import com.routinepilot.domain.sync.FieldMap
import com.routinepilot.domain.sync.MutationKind
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.PlannedChange
import com.routinepilot.domain.sync.ServerChange
import com.routinepilot.domain.sync.SubmitResult
import com.routinepilot.domain.sync.SyncTransport
import java.util.UUID

/**
 * A reference model of the backend's sync rules (backend/src/main/kotlin, SyncDecider and SyncService). A change
 * applies only when its base version is the current version. A repeated change id returns the stored answer only when
 * the operation, base version, and payload all match, and is rejected otherwise. A delete leaves a tombstone. Every
 * accepted change goes into one log, and the log order is the server order.
 */
class FakeSyncServer : SyncTransport {
    private class Head(
        val version: Long,
        val deleted: Boolean,
        val fields: FieldMap,
    )

    private class Accepted(
        val key: ObjectKey,
        val kind: MutationKind,
        val baseVersion: Long,
        val payload: FieldMap?,
        val version: Long,
    )

    private val heads = mutableMapOf<ObjectKey, Head>()
    private val log = mutableListOf<ServerChange>()
    private val acceptedByChangeId = mutableMapOf<UUID, Accepted>()

    override suspend fun submit(change: PlannedChange): SubmitResult {
        val earlier = acceptedByChangeId[change.mutationId]
        return if (earlier != null) replay(earlier, change) else acceptNew(change)
    }

    /** A repeat of an accepted change returns the original answer, or is refused when its id was reused. */
    private fun replay(
        earlier: Accepted,
        change: PlannedChange,
    ): SubmitResult {
        val same =
            earlier.key == change.key &&
                earlier.kind == change.kind &&
                earlier.baseVersion == change.baseVersion &&
                earlier.payload == change.payload
        return if (same) {
            SubmitResult.Accepted(earlier.version, duplicate = true)
        } else {
            SubmitResult.Rejected("change_id_reused")
        }
    }

    private fun acceptNew(change: PlannedChange): SubmitResult {
        val head = heads[change.key]
        val current = head?.version ?: 0L
        if (change.baseVersion != current) {
            val state = head?.let { BaseState(it.version, it.deleted, it.fields) } ?: BaseState.NEVER_SEEN
            return SubmitResult.Conflict(state)
        }
        val deleted = change.kind == MutationKind.DELETE
        val fields = if (deleted) emptyMap() else change.payload.orEmpty()
        val version = current + 1
        heads[change.key] = Head(version, deleted, fields)
        log += ServerChange(log.size + 1L, change.mutationId, change.key, version, deleted, fields)
        acceptedByChangeId[change.mutationId] =
            Accepted(change.key, change.kind, change.baseVersion, change.payload, version)
        return SubmitResult.Accepted(version, duplicate = false)
    }

    fun head(key: ObjectKey): BaseState? = heads[key]?.let { BaseState(it.version, it.deleted, it.fields) }

    fun changesAfter(seq: Long): List<ServerChange> = log.filter { it.seq > seq }

    fun logSize(): Int = log.size
}
