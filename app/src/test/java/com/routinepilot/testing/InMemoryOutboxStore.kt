package com.routinepilot.testing

import com.routinepilot.domain.sync.BaseState
import com.routinepilot.domain.sync.Mutation
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.OutboxStore
import java.util.UUID

/** An [OutboxStore] held in memory, with the same contract as the Room store. */
class InMemoryOutboxStore : OutboxStore {
    private val bases = mutableMapOf<ObjectKey, BaseState>()
    private val queue = mutableListOf<Mutation>()
    private var lastSequence = 0L

    override suspend fun base(key: ObjectKey): BaseState? = bases[key]

    override suspend fun saveBase(key: ObjectKey, state: BaseState) {
        bases[key] = state
    }

    override suspend fun pending(key: ObjectKey): List<Mutation> =
        queue.filter { it.key == key }.sortedBy { it.localSequence }

    override suspend fun findPending(mutationId: UUID): Mutation? =
        queue.firstOrNull { it.mutationId == mutationId }

    override suspend fun append(mutation: Mutation): Mutation {
        findPending(mutation.mutationId)?.let { return it }
        val stored = mutation.copy(localSequence = ++lastSequence)
        queue += stored
        return stored
    }

    override suspend fun replace(mutation: Mutation) {
        val index = queue.indexOfFirst { it.mutationId == mutation.mutationId }
        require(index >= 0) { "No queued change ${mutation.mutationId}" }
        queue[index] = mutation.copy(localSequence = queue[index].localSequence)
    }

    override suspend fun remove(mutationId: UUID) {
        queue.removeAll { it.mutationId == mutationId }
    }

    override suspend fun removeAllFor(key: ObjectKey) {
        queue.removeAll { it.key == key }
    }

    override suspend fun keysWithPending(): List<ObjectKey> = queue.map { it.key }.distinct()

    override suspend fun pendingCount(): Int = queue.size
}
