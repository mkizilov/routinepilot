package com.routinepilot.data.sync

import com.routinepilot.domain.sync.AccountSessions
import com.routinepilot.domain.sync.AuthProblem
import com.routinepilot.domain.sync.DeleteAccountOutcome
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
class AccountSessionManager
    @Inject
    constructor(
        private val client: BackendClient,
        private val account: SyncAccountStore,
        private val outbox: OutboxStore,
        private val database: RoutinePilotDatabase,
        private val seeder: RecordSeeder,
        private val scheduler: SyncScheduler,
    ) : AccountSessions {
        override val email: Flow<String?> = account.email

        override suspend fun signIn(
            email: String,
            password: String,
        ): SignInOutcome = establish(email) { client.login(email, password) }

        override suspend fun register(
            email: String,
            password: String,
        ): SignInOutcome = establish(email) { client.register(email, password) }

        private suspend fun establish(
            email: String,
            authenticate: suspend () -> TokenDto,
        ): SignInOutcome =
            try {
                completeSignIn(email, authenticate)
            } catch (refused: BackendException) {
                SignInOutcome.Failed(problemOf(refused))
            } catch (expectedNetworkFailure: IOException) {
                SignInOutcome.Failed(AuthProblem.UNAVAILABLE)
            }

        private suspend fun completeSignIn(
            email: String,
            authenticate: suspend () -> TokenDto,
        ): SignInOutcome {
            val login = authenticate()
            val accountId = client.me(login.token).id
            val plan = planSignIn(account.owner(), accountId, outbox.pendingCount())
            if (plan is SignInPlan.Blocked) {
                closeUnusedSession(login.token)
                return SignInOutcome.Blocked(plan.unsyncedChanges)
            }
            applySignInPlan(plan, accountId)
            account.saveAccount(email, login.token, Instant.parse(login.expiresAt).toEpochMilli())
            val device = client.registerDevice(login.token, DEVICE_NAME, PLATFORM)
            account.saveDevice(device.device.id, device.token, Instant.parse(device.expiresAt).toEpochMilli())
            scheduler.requestNow()
            scheduler.startPeriodic()
            return SignInOutcome.SignedIn
        }

        private suspend fun applySignInPlan(
            plan: SignInPlan,
            accountId: String,
        ) {
            when (plan) {
                is SignInPlan.Blocked -> Unit
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

        /**
         * Deletes the account on the server, then erases this device's data. Nothing is erased unless the server
         * confirms.
         * Queued changes that were never sent are lost with the account, and the account screen says so
         * before this runs.
         */
        override suspend fun deleteAccount(password: String): DeleteAccountOutcome {
            val token = account.accountToken() ?: return DeleteAccountOutcome.Failed(AuthProblem.SIGN_IN_AGAIN)
            return try {
                client.deleteAccount(token, password)
                withContext(Dispatchers.IO) { database.clearAllTables() }
                account.forgetAccount()
                scheduler.cancelAll()
                DeleteAccountOutcome.Deleted
            } catch (refused: BackendException) {
                DeleteAccountOutcome.Failed(problemOf(refused))
            } catch (expectedNetworkFailure: IOException) {
                DeleteAccountOutcome.Failed(AuthProblem.UNAVAILABLE)
            }
        }

        /** A login that was refused is closed, so it does not stay usable until it expires. */
        private suspend fun closeUnusedSession(token: String) {
            attempt { client.logout(token) }
        }

        private suspend fun attempt(call: suspend () -> Unit): Boolean =
            try {
                call()
                true
            } catch (expectedFailure: BackendException) {
                false
            } catch (expectedNetworkFailure: IOException) {
                false
            }

        private fun problemOf(refused: BackendException): AuthProblem =
            when (refused.code) {
                "invalid_credentials" -> AuthProblem.INVALID_CREDENTIALS
                "email_taken" -> AuthProblem.EMAIL_TAKEN
                "validation_failed" -> AuthProblem.INVALID_INPUT
                "invalid_password" -> AuthProblem.WRONG_PASSWORD
                "unauthorized" -> AuthProblem.SIGN_IN_AGAIN
                else -> AuthProblem.SERVER
            }

        private companion object {
            const val DEVICE_NAME = "Android device"
            const val PLATFORM = "android"
        }
    }
