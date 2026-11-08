package com.routinepilot.testing

import com.routinepilot.domain.sync.AuthExpiredException
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.PlannedChange
import com.routinepilot.domain.sync.RemotePage
import com.routinepilot.domain.sync.RemoteRejectedException
import com.routinepilot.domain.sync.SubmitResult
import com.routinepilot.domain.sync.SyncRemote
import java.io.IOException

/**
 * The network between a simulated device and a [FakeSyncServer]. Tests switch on the failures they
 * need. A dropped response
 * is applied on the server first, which is the case the retry rules must handle.
 */
class FakeSyncRemote(private val server: FakeSyncServer) : SyncRemote {
    var offline = false
    var authExpired = false
    var dropNextResponse = false
    /** Records whose changes the server refuses as invalid. */
    var rejectKeys: Set<ObjectKey> = emptySet()
    var calls = 0
        private set

    override suspend fun submit(change: PlannedChange): SubmitResult {
        calls++
        checkReachable()
        if (change.key in rejectKeys) {
            throw RemoteRejectedException(change.mutationId, "payload is invalid")
        }
        val result = server.submit(change)
        if (dropNextResponse) {
            dropNextResponse = false
            throw IOException("response lost after the server applied the change")
        }
        return result
    }

    override suspend fun pull(afterSeq: Long, limit: Int): RemotePage {
        calls++
        checkReachable()
        val all = server.changesAfter(afterSeq)
        val page = all.take(limit)
        return RemotePage(page, cursor = page.lastOrNull()?.seq ?: afterSeq, hasMore = all.size > limit)
    }

    private fun checkReachable() {
        if (authExpired) throw AuthExpiredException()
        if (offline) throw IOException("offline")
    }
}
