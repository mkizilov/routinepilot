package com.routinepilot.domain.sync

import java.util.UUID

/**
 * A conflict found while merging. [Superseded] resolves itself and is reported. The other two hold the record's queue
 * until the user chooses a [Decision].
 */
sealed interface Conflict {
    val key: ObjectKey
    val mutationId: UUID

    /**
     * Resolved automatically. Two devices changed the same field. The change that reaches the server later replaces the
     * value, so it wins, and the value it replaced is reported.
     */
    data class Superseded(
        override val key: ObjectKey,
        override val mutationId: UUID,
        val field: String,
        val replacedValue: String?,
        val keptValue: String?,
    ) : Conflict

    /**
     * Needs the user. This device edited a record that the server has since deleted. The edit waits. The user restores
     * it with [Decision.RESTORE_EDITS], or drops it with [Decision.DISCARD_EDITS].
     */
    data class EditOfDeleted(
        override val key: ObjectKey,
        override val mutationId: UUID,
    ) : Conflict

    /**
     * Needs the user. This device deleted a record that another device edited after this device last saw it. The delete
     * waits. The user keeps the edit with [Decision.KEEP_EDIT], or deletes anyway with [Decision.DELETE_ANYWAY].
     */
    data class DeleteOfEdited(
        override val key: ObjectKey,
        override val mutationId: UUID,
        val remoteVersion: Long,
    ) : Conflict
}

enum class Decision { RESTORE_EDITS, DISCARD_EDITS, KEEP_EDIT, DELETE_ANYWAY }

/**
 * A change to send next. [baseVersion] is the version it names. [payload] is the whole record after it, or null for a
 * delete.
 */
data class PlannedChange(
    val mutationId: UUID,
    val key: ObjectKey,
    val kind: MutationKind,
    val baseVersion: Long,
    val payload: FieldMap?,
)

/**
 * The outcome of replaying one record's queue on top of the server state. [fields] is the local view. [planned]
 * lists the changes to send, in order. [blocked] means a conflict needs a decision, and the changes after it wait.
 * [redundant] lists queued deletes of records that are already deleted, which can be dropped. [nextVersion] is the
 * version the server will hold after the planned changes.
 */
data class MergeResult(
    val key: ObjectKey,
    val exists: Boolean,
    val fields: FieldMap,
    val planned: List<PlannedChange>,
    val conflicts: List<Conflict>,
    val blocked: Boolean,
    val redundant: List<UUID>,
    val nextVersion: Long,
)

/**
 * Deterministic merge. The result depends only on the server state and the queued changes, never on which device runs
 * it, when it runs, or any device clock. Queued changes replay in local order on top of the server state.
 *
 * A change's request is a function of the base version and the queue before it, so a retry rebuilds the same request.
 * This holds because the server's version order for a record is also the device's order: while a request is unanswered,
 * the device cannot move its base past that request without first absorbing its own log entry.
 */
object OfflineMerge {
    fun merge(
        key: ObjectKey,
        base: BaseState?,
        pending: List<Mutation>,
    ): MergeResult {
        val state = MergeState(key, base)
        for (mutation in pending.sortedBy { it.localSequence }) {
            val stop =
                when {
                    mutation.kind == MutationKind.DELETE -> state.applyDelete(mutation)
                    mutation.expectation == Expectation.PRESENT -> state.applyEdit(mutation)
                    else -> state.applyWholeRecord(mutation)
                }
            if (stop) break
        }
        return state.result()
    }
}

/**
 * The merge's running state. Queued changes are applied in local order. A change that needs the user stops the merge
 * there, so the changes after it wait.
 */
private class MergeState(
    private val key: ObjectKey,
    private val base: BaseState?,
) {
    private val remote = base ?: BaseState.NEVER_SEEN
    private val remoteVersion = remote.version
    private var deleted = remote.deleted
    private var fields: MutableMap<String, String?> = if (deleted) mutableMapOf() else remote.fields.toMutableMap()
    private var version = remote.version
    private val planned = mutableListOf<PlannedChange>()
    private val conflicts = mutableListOf<Conflict>()
    private val redundant = mutableListOf<UUID>()
    private var blocked = false

    /** Returns true when the merge must stop here. */
    fun applyDelete(mutation: Mutation): Boolean {
        if (deleted) {
            redundant += mutation.mutationId
            return false
        }
        if (remoteVersion > mutation.seenVersion) {
            conflicts += Conflict.DeleteOfEdited(key, mutation.mutationId, remoteVersion)
            blocked = true
            return true
        }
        planned += PlannedChange(mutation.mutationId, key, MutationKind.DELETE, version, null)
        fields = mutableMapOf()
        deleted = true
        version++
        return false
    }

    /** Returns true when the merge must stop here. */
    fun applyEdit(mutation: Mutation): Boolean {
        if (deleted) {
            conflicts += Conflict.EditOfDeleted(key, mutation.mutationId)
            blocked = true
            return true
        }
        for ((name, change) in mutation.changes) {
            val current = fields[name]
            if (current == change.after) continue
            if (current != change.before) {
                conflicts += Conflict.Superseded(key, mutation.mutationId, name, current, change.after)
            }
            fields[name] = change.after
        }
        planned += PlannedChange(mutation.mutationId, key, MutationKind.UPSERT, version, fields.toMap())
        version++
        return false
    }

    /** A whole-record write from a record that was not live on this device. It never stops the merge. */
    fun applyWholeRecord(mutation: Mutation): Boolean {
        if (base != null && !deleted) {
            for (name in fields.keys + mutation.snapshot.keys) {
                val current = fields[name]
                val kept = mutation.snapshot[name]
                if (current != kept) {
                    conflicts += Conflict.Superseded(key, mutation.mutationId, name, current, kept)
                }
            }
        }
        fields = mutation.snapshot.toMutableMap()
        deleted = false
        planned += PlannedChange(mutation.mutationId, key, MutationKind.UPSERT, version, fields.toMap())
        version++
        return false
    }

    fun result(): MergeResult {
        val exists = !deleted && (base != null || fields.isNotEmpty())
        return MergeResult(
            key = key,
            exists = exists,
            fields = if (exists) fields.toMap() else emptyMap(),
            planned = planned,
            conflicts = conflicts,
            blocked = blocked,
            redundant = redundant,
            nextVersion = version,
        )
    }
}
