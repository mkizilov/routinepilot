package com.routinepilot.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountIsolationTest {
    @Test
    fun deviceWithoutAnOwner_adoptsTheAccount_evenWithQueuedChanges() {
        assertEquals(SignInPlan.Adopt, planSignIn(owner = null, account = "mary", unsyncedChanges = 5))
    }

    @Test
    fun sameOwnerSigningInAgain_keepsEverything() {
        assertEquals(SignInPlan.Adopt, planSignIn(owner = "mary", account = "mary", unsyncedChanges = 3))
    }

    @Test
    fun otherAccountWithUnsentChanges_blocksTheSignIn_andErasesNothing() {
        assertEquals(SignInPlan.Blocked(2), planSignIn(owner = "mary", account = "sam", unsyncedChanges = 2))
    }

    @Test
    fun otherAccountWithEverythingSent_erasesLocalDataBeforeSignIn() {
        assertEquals(SignInPlan.ReplaceLocalData, planSignIn(owner = "mary", account = "sam", unsyncedChanges = 0))
    }
}
