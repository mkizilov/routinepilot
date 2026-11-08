package com.routinepilot.data.sync

import com.routinepilot.domain.sync.AuthExpiredException
import com.routinepilot.domain.sync.PlannedChange
import com.routinepilot.domain.sync.RemotePage
import com.routinepilot.domain.sync.RemoteRejectedException
import com.routinepilot.domain.sync.SubmitResult
import com.routinepilot.domain.sync.SyncRemote
import java.time.Instant
import javax.inject.Inject

/**
 * Talks to the backend for the signed-in account. Sync calls use a device session. When that session has expired by its
 * stored expiry time, the device is registered again with the account session, once. When the server rejects a device
 * session that has not expired, the device was revoked, so the user is asked to sign in
 * rather than re-adding the device.
 */
class BackendSyncRemote @Inject constructor(
    private val client: BackendClient,
    private val account: SyncAccountStore,
) : SyncRemote {
    override suspend fun submit(change: PlannedChange): SubmitResult {
        val response = try {
            withDeviceSession { token -> client.upload(token, UploadDto(listOf(change.toWire()))) }
        } catch (refused: BackendException) {
            if (refused.status == HTTP_BAD_REQUEST) {
                throw RemoteRejectedException(change.mutationId, refused.message.orEmpty())
            }
            throw refused
        }
        return response.results.single().toSubmitResult()
    }

    override suspend fun pull(afterSeq: Long, limit: Int): RemotePage {
        val page = withDeviceSession { token -> client.pull(token, afterSeq, limit) }
        return RemotePage(
            changes = page.changes.mapNotNull { it.toServerChange() },
            cursor = page.cursor,
            hasMore = page.hasMore,
        )
    }

    private suspend fun <T> withDeviceSession(call: suspend (String) -> T): T {
        val token = account.deviceToken() ?: registerDevice()
        return try {
            call(token)
        } catch (rejected: BackendException) {
            if (rejected.status != HTTP_UNAUTHORIZED && rejected.status != HTTP_FORBIDDEN) throw rejected
            if (!account.deviceTokenExpired()) {
                account.clearDevice()
                throw AuthExpiredException()
            }
            call(registerDevice())
        }
    }

    private suspend fun registerDevice(): String {
        val accountToken = account.accountToken()
        if (accountToken == null || account.accountTokenExpired()) throw AuthExpiredException()
        val registered = try {
            client.registerDevice(accountToken, DEVICE_NAME, PLATFORM)
        } catch (rejected: BackendException) {
            if (rejected.status == HTTP_UNAUTHORIZED || rejected.status == HTTP_FORBIDDEN) throw AuthExpiredException()
            throw rejected
        }
        account.saveDevice(registered.device.id, registered.token, Instant.parse(registered.expiresAt).toEpochMilli())
        return registered.token
    }

    private companion object {
        const val DEVICE_NAME = "Android device"
        const val PLATFORM = "android"
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
