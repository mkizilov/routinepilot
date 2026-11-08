package com.routinepilot.domain.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Writes a record's merged view into the app's own tables. These writes never create outbox entries. */
interface LocalRecordWriter {
    suspend fun write(view: MergeResult)
}

enum class SyncPhase { NOT_SIGNED_IN, SYNCING, UP_TO_DATE, WAITING_FOR_NETWORK, NEEDS_SIGN_IN, NEEDS_ATTENTION }

/**
 * What the sync screen shows. [heldConflicts] are edits or deletes that wait for the user.
 * [rejectedChanges] were refused by
 * the server and stay on this device.
 */
data class SyncState(
    val phase: SyncPhase,
    val pendingChanges: Int,
    val lastSyncedAtMillis: Long?,
    val heldConflicts: List<Conflict> = emptyList(),
    val rejectedChanges: Int = 0,
)

sealed interface CycleResult {
    data object Done : CycleResult

    data object NotSignedIn : CycleResult

    /** The session expired. Nothing was lost, and the next cycle waits until the user signs in again. */
    data object NeedsSignIn : CycleResult

    /** The server could not be reached. Nothing was lost, and the next cycle should run when the connection returns. */
    data object Retry : CycleResult
}

/**
 * One sync pass: pull what the server has, send each queued record's changes in order, then pull again.
 * A failure at any
 * point leaves the outbox and the base in a state the next pass can continue from, so a cycle can stop anywhere.
 */
class SyncEngine(
    private val outbox: OutboxStore,
    private val remote: SyncRemote,
    private val session: SyncSessionStore,
    private val credentials: SyncCredentials,
    private val localData: LocalRecordWriter,
    private val nowMillis: () -> Long,
) {
    private val offline = OfflineSync(outbox, remote)

    /** One operation at a time. A background pass and a user action must never interleave on the same outbox. */
    private val lock = Mutex()
    private val mutableState =
        MutableStateFlow(
            SyncState(SyncPhase.UP_TO_DATE, pendingChanges = 0, lastSyncedAtMillis = null),
        )
    val state: StateFlow<SyncState> = mutableState.asStateFlow()

    /** Refreshes the sign-in and pending counts without contacting the server. */
    suspend fun refreshState() =
        lock.withLock {
            val current = mutableState.value
            val phase =
                when {
                    !credentials.accountSignedIn() -> SyncPhase.NOT_SIGNED_IN
                    current.phase == SyncPhase.NOT_SIGNED_IN -> SyncPhase.UP_TO_DATE
                    else -> current.phase
                }
            publish(phase, current.heldConflicts, current.rejectedChanges)
        }

    suspend fun runCycle(): CycleResult = lock.withLock { cycle() }

    private suspend fun cycle(): CycleResult {
        if (!credentials.accountSignedIn()) {
            publish(SyncPhase.NOT_SIGNED_IN)
            return CycleResult.NotSignedIn
        }
        publish(SyncPhase.SYNCING)
        val touched = mutableSetOf<ObjectKey>()
        val held = mutableListOf<Conflict>()
        var rejected = 0
        try {
            pullAll(touched)
            for (key in outbox.keysWithPending()) {
                touched += key
                try {
                    val merged = offline.flush(key)
                    if (merged.blocked) held += merged.conflicts.filterNot { it is Conflict.Superseded }
                } catch (refused: RemoteRejectedException) {
                    rejected++
                }
            }
            pullAll(touched)
        } catch (expired: AuthExpiredException) {
            applyTouched(touched)
            publish(SyncPhase.NEEDS_SIGN_IN, held, rejected)
            return CycleResult.NeedsSignIn
        } catch (busy: IOException) {
            applyTouched(touched)
            publish(SyncPhase.WAITING_FOR_NETWORK, held, rejected)
            return CycleResult.Retry
        }
        applyTouched(touched)
        session.markSynced(nowMillis())
        val phase = if (held.isEmpty() && rejected == 0) SyncPhase.UP_TO_DATE else SyncPhase.NEEDS_ATTENTION
        publish(phase, held, rejected)
        return CycleResult.Done
    }

    /** Applies the user's choice for a held change, then shows the result. The change is sent by the next cycle. */
    suspend fun resolve(
        conflict: Conflict,
        decision: Decision,
    ) = lock.withLock {
        offline.resolve(conflict, decision)
        applyTouched(setOf(conflict.key))
        val remaining = mutableState.value.heldConflicts.filterNot { it.mutationId == conflict.mutationId }
        val rejected = mutableState.value.rejectedChanges
        val phase = if (remaining.isEmpty() && rejected == 0) SyncPhase.UP_TO_DATE else SyncPhase.NEEDS_ATTENTION
        publish(phase, remaining, rejected)
    }

    /** Applies server changes without sending anything. Used by the first pull after sign-in and by tests. */
    suspend fun pullRemote() =
        lock.withLock {
            val touched = mutableSetOf<ObjectKey>()
            pullAll(touched)
            applyTouched(touched)
        }

    private suspend fun pullAll(touched: MutableSet<ObjectKey>) {
        do {
            val page = remote.pull(session.cursor(), PAGE_SIZE)
            for (change in page.changes) {
                if (offline.absorb(change)) touched += change.key
            }
            session.saveCursor(page.cursor)
        } while (page.hasMore)
    }

    private suspend fun applyTouched(keys: Set<ObjectKey>) {
        for (key in keys) {
            localData.write(OfflineMerge.merge(key, outbox.base(key), outbox.pending(key)))
        }
    }

    private suspend fun publish(
        phase: SyncPhase,
        held: List<Conflict> = emptyList(),
        rejected: Int = 0,
    ) {
        mutableState.value =
            SyncState(
                phase = phase,
                pendingChanges = outbox.pendingCount(),
                lastSyncedAtMillis = session.lastSyncedAt(),
                heldConflicts = held,
                rejectedChanges = rejected,
            )
    }

    private companion object {
        const val PAGE_SIZE = 200
    }
}
