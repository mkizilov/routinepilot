package com.routinepilot.domain.sync

import java.io.IOException
import java.util.UUID

/** The server side of sync: uploads (through [SyncTransport]) and reading the change log. */
interface SyncRemote : SyncTransport {
    suspend fun pull(
        afterSeq: Long,
        limit: Int,
    ): RemotePage
}

/** One page of the server's change log. [cursor] is where to continue, even when some entries were not synced types. */
data class RemotePage(
    val changes: List<ServerChange>,
    val cursor: Long,
    val hasMore: Boolean,
)

/** The session is gone or was revoked. Queued changes stay on the device until the user signs in again. */
class AuthExpiredException : RuntimeException("Sign in again to resume sync")

/** The server refused one change as invalid. The change stays queued and is reported. */
class RemoteRejectedException(
    val mutationId: UUID,
    val reason: String,
    cause: Throwable? = null,
) : RuntimeException("Server refused change $mutationId: $reason", cause)

/** The server is busy or unreachable for now. Worth trying again later. */
class RemoteBusyException(
    message: String,
) : IOException(message)
