package com.routinepilot.domain.sync

import java.util.UUID

/**
 * Records local edits to the outbox. Repositories call [record] inside the same database transaction as the write it
 * describes, so the outbox and the local record change together. Nothing is recorded when the tracked fields did not
 * change.
 */
class OfflineReplica(
    private val store: OutboxStore,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    /**
     * Records a local change to one record. [before] and [after] are the tracked fields before and after the edit.
     * A null [after] is a delete. A null [before] means the record was not live on this device, so the change writes
     * the whole record.
     */
    suspend fun record(
        key: ObjectKey,
        before: FieldMap?,
        after: FieldMap?,
        nowMillis: Long,
    ) {
        if (before == null && after == null) return
        val changes = if (before != null && after != null) diff(before, after) else emptyMap()
        if (before != null && after != null && changes.isEmpty()) return

        val base = store.base(key)
        val editsKnownLive = before != null && base != null && !base.deleted && queueLeavesRecordLive(key)
        store.append(
            Mutation(
                mutationId = newId(),
                key = key,
                localSequence = 0,
                seenVersion = base?.version ?: 0L,
                kind = if (after == null) MutationKind.DELETE else MutationKind.UPSERT,
                expectation = if (editsKnownLive) Expectation.PRESENT else Expectation.ABSENT,
                snapshot = after.orEmpty(),
                changes = changes,
                createdAtMillis = nowMillis,
            ),
        )
    }

    /** True unless the last queued change deletes the record, in which case a new edit cannot assume it is live. */
    private suspend fun queueLeavesRecordLive(key: ObjectKey): Boolean =
        store.pending(key).lastOrNull()?.let { it.kind == MutationKind.UPSERT } ?: true

    private fun diff(
        before: FieldMap,
        after: FieldMap,
    ): Map<String, FieldChange> =
        (before.keys + after.keys)
            .mapNotNull { name ->
                val old = before[name]
                val new = after[name]
                if (old == new) null else name to FieldChange(old, new)
            }.toMap()
}
