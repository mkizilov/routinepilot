package com.routinepilot.data.sync

import com.routinepilot.domain.sync.BaseState
import com.routinepilot.domain.sync.FieldChange
import com.routinepilot.domain.sync.Mutation
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.OutboxStore
import com.routinepilot.infrastructure.database.MutationFieldEntity
import com.routinepilot.infrastructure.database.MutationOutboxEntity
import com.routinepilot.infrastructure.database.SyncBaseEntity
import com.routinepilot.infrastructure.database.SyncBaseFieldEntity
import com.routinepilot.infrastructure.database.SyncOutboxDao
import java.util.UUID

/**
 * Room storage for the outbox and the last server state. Callers run it inside their transaction, so a local edit and
 * its queued change are saved together.
 */
class RoomOutboxStore(private val dao: SyncOutboxDao) : OutboxStore {
    override suspend fun base(key: ObjectKey): BaseState? {
        val row = dao.findBase(key.type, key.id) ?: return null
        val fields = dao.baseFieldsOf(key.type, key.id).associate { it.fieldName to it.fieldValue }
        return BaseState(row.version, row.deleted, fields)
    }

    override suspend fun saveBase(key: ObjectKey, state: BaseState) {
        dao.upsertBase(SyncBaseEntity(key.type, key.id, state.version, state.deleted))
        dao.deleteBaseFields(key.type, key.id)
        dao.insertBaseFields(state.fields.map { (name, value) -> SyncBaseFieldEntity(key.type, key.id, name, value) })
    }

    override suspend fun pending(key: ObjectKey): List<Mutation> =
        dao.pendingFor(key.type, key.id).map { toDomain(it) }

    override suspend fun findPending(mutationId: UUID): Mutation? = dao.findMutation(mutationId)?.let { toDomain(it) }

    override suspend fun append(mutation: Mutation): Mutation {
        val rowId = dao.insertMutation(
            MutationOutboxEntity(
                mutationId = mutation.mutationId,
                objectType = mutation.key.type,
                objectId = mutation.key.id,
                seenVersion = mutation.seenVersion,
                kind = mutation.kind,
                expectation = mutation.expectation,
                createdAtMillis = mutation.createdAtMillis,
            ),
        )
        if (rowId == IGNORED_ROW) return checkNotNull(findPending(mutation.mutationId))
        val names = mutation.snapshot.keys + mutation.changes.keys
        dao.insertFields(
            names.map { name ->
                MutationFieldEntity(
                    localSequence = rowId,
                    fieldName = name,
                    beforeValue = mutation.changes[name]?.before,
                    afterValue = mutation.snapshot[name],
                    changed = name in mutation.changes,
                )
            },
        )
        return mutation.copy(localSequence = rowId)
    }

    override suspend fun replace(mutation: Mutation) {
        val row = checkNotNull(dao.findMutation(mutation.mutationId)) { "No queued change ${mutation.mutationId}" }
        dao.updateSeenVersion(row.localSequence, mutation.seenVersion)
    }

    override suspend fun remove(mutationId: UUID) {
        dao.deleteMutation(mutationId)
    }

    override suspend fun removeAllFor(key: ObjectKey) {
        dao.deleteAllFor(key.type, key.id)
    }

    private suspend fun toDomain(row: MutationOutboxEntity): Mutation {
        val fields = dao.fieldsOf(row.localSequence)
        return Mutation(
            mutationId = row.mutationId,
            key = ObjectKey(row.objectType, row.objectId),
            localSequence = row.localSequence,
            seenVersion = row.seenVersion,
            kind = row.kind,
            expectation = row.expectation,
            snapshot = fields.associate { it.fieldName to it.afterValue },
            changes = fields.filter { it.changed }
                .associate { it.fieldName to FieldChange(it.beforeValue, it.afterValue) },
            createdAtMillis = row.createdAtMillis,
        )
    }

    private companion object {
        /** Room returns -1 when OnConflictStrategy.IGNORE skips an insert, which happens for a repeated mutation id. */
        const val IGNORED_ROW = -1L
    }
}
