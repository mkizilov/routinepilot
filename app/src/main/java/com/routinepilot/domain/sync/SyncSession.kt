package com.routinepilot.domain.sync

/** Whether this device can talk to the server for its account. Tokens themselves never leave the data layer. */
interface SyncCredentials {
    /** True while the account session is stored and not expired. */
    suspend fun accountSignedIn(): Boolean
}

/**
 * Sync progress for the signed-in account. The cursor is a position in that account's change log. It belongs to the
 * account, so it is reset whenever the device's data changes owner.
 */
interface SyncSessionStore {
    suspend fun cursor(): Long

    suspend fun saveCursor(seq: Long)

    suspend fun markSynced(atMillis: Long)

    suspend fun lastSyncedAt(): Long?
}
