package com.routinepilot.testing

import com.routinepilot.domain.sync.Conflict
import com.routinepilot.domain.sync.CycleResult
import com.routinepilot.domain.sync.Decision
import com.routinepilot.domain.sync.FieldMap
import com.routinepilot.domain.sync.LocalRecordWriter
import com.routinepilot.domain.sync.MergeResult
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.OfflineMerge
import com.routinepilot.domain.sync.OfflineReplica
import com.routinepilot.domain.sync.OfflineSync
import com.routinepilot.domain.sync.SyncEngine
import java.util.UUID

/**
 * One device with its own outbox, account state, and app data, syncing through [remote]. [namespace] keeps mutation ids
 * unique across devices. The app data is written by the same [LocalRecordWriter] contract the Room writer implements.
 */
class SimulatedDevice(
    val name: String,
    server: FakeSyncServer,
    namespace: Long,
    val remote: FakeSyncRemote = FakeSyncRemote(server),
) {
    private var counter = 0L
    private val store = InMemoryOutboxStore()
    val account = InMemorySyncAccount()
    private val replica = OfflineReplica(store) { UUID(namespace, ++counter) }
    private val syncer = OfflineSync(store, remote) { UUID(namespace, ++counter) }
    private val local = mutableMapOf<ObjectKey, FieldMap>()
    private val writer = object : LocalRecordWriter {
        override suspend fun write(view: MergeResult) {
            if (view.exists) local[view.key] = view.fields else local.remove(view.key)
        }
    }
    private val engine = SyncEngine(store, remote, account, account, writer) { clockMillis }

    /** The device's clock. Tests set it to show that merge outcomes do not depend on it. */
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

    /** Sends one record's queued changes, without the rest of a pass. */
    suspend fun sync(key: ObjectKey): MergeResult = syncer.flush(key).also { writer.write(it) }

    /** Applies every server change this device has not seen yet. */
    suspend fun pull() = engine.pullRemote()

    /** A full pass, as the background job runs it. */
    suspend fun cycle(): CycleResult = engine.runCycle()

    val syncState get() = engine.state

    suspend fun resolve(conflict: Conflict, decision: Decision) = engine.resolve(conflict, decision)

    suspend fun view(key: ObjectKey): MergeResult = OfflineMerge.merge(key, store.base(key), store.pending(key))

    suspend fun queued(key: ObjectKey): Int = store.pending(key).size

    suspend fun queuedTotal(): Int = store.pendingCount()
}
