package com.routinepilot.data.sync

import com.routinepilot.domain.sync.AccountSessions
import com.routinepilot.domain.sync.AuthProblem
import com.routinepilot.domain.sync.OutboxStore
import com.routinepilot.domain.sync.SignInOutcome
import com.routinepilot.domain.sync.SignInPlan
import com.routinepilot.domain.sync.SignOutOutcome
import com.routinepilot.domain.sync.planSignIn
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

/**
 * Signs the device in and out. Sign-in decides what happens to local data first (see [planSignIn]), so another
 * account's
 * records never reach this one. Sign-out keeps local data and queued
 * changes, so the app keeps working offline and nothing
 * unsent is lost.
 */
class AccountSessionManager @Inject constructor(
    private val client: BackendClient,
    private val account: SyncAccountStore,
    private val outbox: OutboxStore,
    private val database: RoutinePilotDatabase,
    private val seeder: RecordSeeder,
    private val scheduler: SyncScheduler,
) : AccountSessions {
    override val email: Flow<String?> = account.email

    override suspend fun signIn(email: String, password: String): SignInOutcome =
        establish(email) { client.login(email, password) }

    override suspend fun register(email: String, password: String): SignInOutcome =
        establish(email) { client.register(email, password) }

    private suspend fun establish(email: String, authenticate: suspend () -> TokenDto): SignInOutcome {
        try {
            val login = authenticate()
            val accountId = client.me(login.token).id
            val owner = account.owner()
            when (val plan = planSignIn(owner, accountId, outbox.pendingCount())) {
                is SignInPlan.Blocked -> {
                    closeUnusedSession(login.token)
                    return SignInOutcome.Blocked(plan.unsyncedChanges)
                }
                SignInPlan.ReplaceLocalData -> {
                    withContext(Dispatchers.IO) { database.clearAllTables() }
                    account.setOwner(accountId)
                    seeder.seedUntrackedRecords()
                }
                SignInPlan.Adopt -> {
                    account.setOwner(accountId)
                    seeder.seedUntrackedRecords()
                }
            }
            account.saveAccount(email, login.token, Instant.parse(login.expiresAt).toEpochMilli())
            val device = client.registerDevice(login.token, DEVICE_NAME, PLATFORM)
            account.saveDevice(device.device.id, device.token, Instant.parse(device.expiresAt).toEpochMilli())
            scheduler.requestNow()
            scheduler.startPeriodic()
            return SignInOutcome.SignedIn
        } catch (refused: BackendException) {
            return SignInOutcome.Failed(problemOf(refused))
        } catch (unreachable: IOException) {
            return SignInOutcome.Failed(AuthProblem.UNAVAILABLE)
        }
    }

    /**
     * Removes this device from the account and closes the session. Both calls are best effort. When the server
     * cannot be
     * reached, the device session expires on its own, and the outcome says so.
     */
    override suspend fun signOut(): SignOutOutcome {
        val token = account.accountToken()
        val deviceId = account.deviceId()
        var deviceRevoked = true
        if (token != null && deviceId != null) {
            deviceRevoked = attempt { client.revokeDevice(token, deviceId) }
        }
        if (token != null) attempt { client.logout(token) }
        account.clearSignIn()
        scheduler.cancelAll()
        return SignOutOutcome(deviceRevoked)
    }

    /** A login that was refused is closed, so it does not stay usable until it expires. */
    private suspend fun closeUnusedSession(token: String) {
        attempt { client.logout(token) }
    }

    private suspend fun attempt(call: suspend () -> Unit): Boolean = try {
        call()
        true
    } catch (failed: BackendException) {
        false
    } catch (unreachable: IOException) {
        false
    }

    private fun problemOf(refused: BackendException): AuthProblem = when (refused.code) {
        "invalid_credentials" -> AuthProblem.INVALID_CREDENTIALS
        "email_taken" -> AuthProblem.EMAIL_TAKEN
        "validation_failed" -> AuthProblem.INVALID_INPUT
        else -> AuthProblem.SERVER
    }

    private companion object {
        const val DEVICE_NAME = "Android device"
        const val PLATFORM = "android"
    }
}
