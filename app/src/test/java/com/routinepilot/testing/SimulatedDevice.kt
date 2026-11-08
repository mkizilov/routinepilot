package com.routinepilot.testing

import com.routinepilot.domain.sync.Conflict
import com.routinepilot.domain.sync.Decision
import com.routinepilot.domain.sync.FieldMap
import com.routinepilot.domain.sync.MergeResult
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.OfflineMerge
import com.routinepilot.domain.sync.OfflineReplica
import com.routinepilot.domain.sync.OfflineSync
import com.routinepilot.domain.sync.SyncTransport
import java.util.UUID

/**
 * One device with its own outbox and app data, syncing with a [FakeSyncServer]. [namespace] keeps mutation ids unique
 * across devices. The app data is written as the repositories would write it. [pull] refreshes it from the merged view,
 * which is the job the sync integration will do.
 */
class SimulatedDevice(
    val name: String,
    private val server: FakeSyncServer,
    namespace: Long,
    transport: SyncTransport = server,
) {
    private var counter = 0L
    private val store = InMemoryOutboxStore()
    private val replica = OfflineReplica(store) { UUID(namespace, ++counter) }
    private val syncer = OfflineSync(store, transport) { UUID(namespace, ++counter) }
    private val local = mutableMapOf<ObjectKey, FieldMap>()
    private var cursor = 0L

    /** The device's clock. It is set by tests to show that merge outcomes do not depend on it. */
    var clockMillis: Long = 0

    /** Saves a record as the app does: the local data changes, then the outbox records the change. */
    suspend fun save(key: ObjectKey, fields: FieldMap) {
        val before = local.put(key, fields)
        replica.record(key, before, fields, clockMillis)
    }

    suspend fun delete(key: ObjectKey) {
        val before = local.remove(key)
        replica.record(key, before, null, clockMillis)
    }

    fun appData(key: ObjectKey): FieldMap? = local[key]

    suspend fun sync(key: ObjectKey): MergeResult = syncer.flush(key).also { refresh(key) }

    /** Applies every server change this device has not seen, in server order. */
    suspend fun pull() {
        for (change in server.changesAfter(cursor)) {
            syncer.absorb(change)
            cursor = change.seq
            refresh(change.key)
        }
    }

    suspend fun resolve(conflict: Conflict, decision: Decision) {
        syncer.resolve(conflict, decision)
        refresh(conflict.key)
    }

    suspend fun view(key: ObjectKey): MergeResult = OfflineMerge.merge(key, store.base(key), store.pending(key))

    suspend fun queued(key: ObjectKey): Int = store.pending(key).size

    private suspend fun refresh(key: ObjectKey) {
        val merged = view(key)
        if (merged.exists) local[key] = merged.fields else local.remove(key)
    }
}
