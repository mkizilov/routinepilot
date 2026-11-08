package com.routinepilot.domain.sync

import java.util.UUID

sealed interface SubmitResult {
    /** [duplicate] means the server already had this change. [version] is the version it was applied at. */
    data class Accepted(
        val version: Long,
        val duplicate: Boolean,
    ) : SubmitResult

    /** The change did not apply. [head] is the server's current state, and the change must be merged again. */
    data class Conflict(
        val head: BaseState,
    ) : SubmitResult

    data class Rejected(
        val reason: String,
    ) : SubmitResult
}

/** The server side of a submission. The real implementation posts the change to the sync API. */
interface SyncTransport {
    suspend fun submit(change: PlannedChange): SubmitResult
}

/**
 * Sends queued changes and applies the answers. A record's changes go out one at a time, in local order, and each
 * waits for its answer. The base moves only on a definitive answer or on a log entry in version order, so an unanswered
 * change is retried by resending the same request.
 */
class OfflineSync(
    private val store: OutboxStore,
    private val transport: SyncTransport,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    /**
     * Sends the record's changes until none can go, or until a conflict needs a user decision. The conflicts seen along
     * the way are returned, so a superseded value is reported even though a later merge no longer sees it.
     */
    suspend fun flush(key: ObjectKey): MergeResult {
        val conflicts = mutableListOf<Conflict>()
        var rounds = 0
        while (true) {
            check(++rounds <= MAX_ROUNDS) { "Sync for $key did not settle after $MAX_ROUNDS rounds" }
            val merged = OfflineMerge.merge(key, store.base(key), store.pending(key))
            conflicts += merged.conflicts
            if (merged.redundant.isNotEmpty()) {
                merged.redundant.forEach { store.remove(it) }
                continue
            }
            val next = merged.planned.firstOrNull() ?: return merged.copy(conflicts = conflicts.distinct())
            when (val result = transport.submit(next)) {
                is SubmitResult.Accepted -> settle(key, next.mutationId, result.version, next.kind, next.payload)
                is SubmitResult.Conflict -> store.saveBase(key, result.head)
                is SubmitResult.Rejected -> throw RemoteRejectedException(next.mutationId, result.reason)
            }
        }
    }

    /**
     * Applies a change from the server's log and reports whether the base moved. If it is one of this device's own
     * changes, it is settled. Any other change moves the base forward, but only in version order, so the device never
     * skips a version it has not seen.
     */
    suspend fun absorb(change: ServerChange): Boolean {
        val kind = if (change.deleted) MutationKind.DELETE else MutationKind.UPSERT
        if (store.findPending(change.changeId) != null) {
            return settle(change.key, change.changeId, change.version, kind, change.fields)
        }
        val base = store.base(change.key) ?: BaseState.NEVER_SEEN
        val inOrder = change.version == base.version + 1
        if (inOrder) store.saveBase(change.key, BaseState(change.version, change.deleted, change.fields))
        return inOrder
    }

    /** Applies a user decision to a held change. A superseded field resolves on its own and takes no decision. */
    suspend fun resolve(
        conflict: Conflict,
        decision: Decision,
    ) {
        val base = store.base(conflict.key) ?: BaseState.NEVER_SEEN
        when (conflict) {
            is Conflict.DeleteOfEdited -> {
                require(decision == Decision.KEEP_EDIT || decision == Decision.DELETE_ANYWAY) {
                    "A delete of an edited record takes KEEP_EDIT or DELETE_ANYWAY, not $decision"
                }
                if (decision == Decision.KEEP_EDIT) {
                    store.remove(conflict.mutationId)
                } else {
                    store.findPending(conflict.mutationId)?.let { store.replace(it.copy(seenVersion = base.version)) }
                }
            }
            is Conflict.EditOfDeleted -> {
                require(decision == Decision.RESTORE_EDITS || decision == Decision.DISCARD_EDITS) {
                    "An edit of a deleted record takes RESTORE_EDITS or DISCARD_EDITS, not $decision"
                }
                val queued = store.pending(conflict.key)
                store.removeAllFor(conflict.key)
                if (decision == Decision.RESTORE_EDITS) {
                    val latest = queued.last { it.kind == MutationKind.UPSERT }
                    store.append(
                        Mutation(
                            mutationId = newId(),
                            key = conflict.key,
                            localSequence = 0,
                            seenVersion = base.version,
                            kind = MutationKind.UPSERT,
                            expectation = Expectation.ABSENT,
                            snapshot = latest.snapshot,
                            changes = emptyMap(),
                            createdAtMillis = latest.createdAtMillis,
                        ),
                    )
                }
            }
            is Conflict.Superseded -> error("A superseded field resolves automatically and takes no decision")
        }
    }

    /**
     * Removes an accepted change and moves the base to its state when that is the next version.
     * A queued change that was
     * made on the version just before this one moves to this version, so it does not report a conflict with its own
     * change. Returns whether the base moved.
     */
    private suspend fun settle(
        key: ObjectKey,
        mutationId: UUID,
        version: Long,
        kind: MutationKind,
        payload: FieldMap?,
    ): Boolean {
        store.remove(mutationId)
        val base = store.base(key) ?: BaseState.NEVER_SEEN
        val advanced = version == base.version + 1
        if (advanced) store.saveBase(key, BaseState(version, kind == MutationKind.DELETE, payload.orEmpty()))
        store
            .pending(key)
            .filter { it.seenVersion == version - 1 }
            .forEach { store.replace(it.copy(seenVersion = version)) }
        return advanced
    }

    private companion object {
        const val MAX_ROUNDS = 100
    }
}
