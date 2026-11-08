package com.routinepilot.domain.sync

import kotlinx.coroutines.flow.Flow

/** What the account screen can do. The data layer does the network calls and the device changes behind it. */
interface AccountSessions {
    /** The signed-in email, or null when signed out. */
    val email: Flow<String?>

    suspend fun signIn(email: String, password: String): SignInOutcome

    suspend fun register(email: String, password: String): SignInOutcome

    suspend fun signOut(): SignOutOutcome

    /**
     * Deletes the account on the server, then erases its data on this device. Nothing on the device is erased unless
     * the server confirms the deletion.
     */
    suspend fun deleteAccount(password: String): DeleteAccountOutcome
}

sealed interface SignInOutcome {
    data object SignedIn : SignInOutcome

    /** The device holds changes for another account that were never sent, so this sign-in was refused. */
    data class Blocked(val unsyncedChanges: Int) : SignInOutcome

    data class Failed(val problem: AuthProblem) : SignInOutcome
}

sealed interface DeleteAccountOutcome {
    data object Deleted : DeleteAccountOutcome

    data class Failed(val problem: AuthProblem) : DeleteAccountOutcome
}

enum class AuthProblem {
    INVALID_CREDENTIALS,
    WRONG_PASSWORD,
    EMAIL_TAKEN,
    INVALID_INPUT,
    SIGN_IN_AGAIN,
    UNAVAILABLE,
    SERVER,
}

/** [deviceRevoked] is false when the server could not be reached to remove this device from the account. */
data class SignOutOutcome(val deviceRevoked: Boolean)
