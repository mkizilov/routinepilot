package com.routinepilot.testing

import com.routinepilot.domain.sync.SyncCredentials
import com.routinepilot.domain.sync.SyncSessionStore

/** The account state of one simulated device, held in memory. [signedIn] stands for a stored, unexpired session. */
class InMemorySyncAccount(var signedIn: Boolean = true) : SyncCredentials, SyncSessionStore {
    private var cursor = 0L
    private var lastSynced: Long? = null

    override suspend fun accountSignedIn(): Boolean = signedIn

    override suspend fun cursor(): Long = cursor

    override suspend fun saveCursor(seq: Long) {
        cursor = seq
    }

    override suspend fun markSynced(atMillis: Long) {
        lastSynced = atMillis
    }

    override suspend fun lastSyncedAt(): Long? = lastSynced
}
