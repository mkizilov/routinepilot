package com.routinepilot.domain.sync

import java.util.UUID

/** Persistence for the outbox and the last known server state. Room implements it, and tests use an in-memory store. */
interface OutboxStore {
    suspend fun base(key: ObjectKey): BaseState?

    suspend fun saveBase(key: ObjectKey, state: BaseState)

    /** Queued changes for one record, in the order this device made them. */
    suspend fun pending(key: ObjectKey): List<Mutation>

    suspend fun findPending(mutationId: UUID): Mutation?

    /**
     * Stores a change and returns it with its local sequence. A change whose mutation id is already stored is not
     * stored again, and the stored change is returned.
     */
    suspend fun append(mutation: Mutation): Mutation

    /**
     * Replaces the part of a queued change that can change after it is recorded, which is [Mutation.seenVersion]. Its
     * snapshot, expectation, and field changes stay as they were recorded.
     */
    suspend fun replace(mutation: Mutation)

    suspend fun remove(mutationId: UUID)

    suspend fun removeAllFor(key: ObjectKey)
}
