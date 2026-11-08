package com.routinepilot.domain.sync

sealed interface SignInPlan {
    /** No account owns this device yet, or the same account is signing in again. Local data is kept. */
    data object Adopt : SignInPlan

    /** A different account owned this device and every change is on the server, so local data is erased first. */
    data object ReplaceLocalData : SignInPlan

    /** A different account owns this device and still has changes that were never sent. Nothing is erased. */
    data class Blocked(
        val unsyncedChanges: Int,
    ) : SignInPlan
}

/**
 * Decides what signing in to [account] does to this device's data. Another account's records must never be uploaded to
 * [account] or shown to it, and no change that was never sent may be erased.
 */
fun planSignIn(
    owner: String?,
    account: String,
    unsyncedChanges: Int,
): SignInPlan =
    when {
        owner == null || owner == account -> SignInPlan.Adopt
        unsyncedChanges > 0 -> SignInPlan.Blocked(unsyncedChanges)
        else -> SignInPlan.ReplaceLocalData
    }
