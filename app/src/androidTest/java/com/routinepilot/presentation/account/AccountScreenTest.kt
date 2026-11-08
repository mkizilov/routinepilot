package com.routinepilot.presentation.account

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.routinepilot.domain.sync.Conflict
import com.routinepilot.domain.sync.Decision
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.SyncPhase
import com.routinepilot.domain.sync.SyncState
import com.routinepilot.domain.sync.SyncType
import com.routinepilot.presentation.theme.RoutinePilotTheme
import com.routinepilot.testing.WithFontScale
import com.routinepilot.testing.assertEveryClickableIsLabelled
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AccountScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun signIn_staysDisabled_untilEmailAndPasswordAreEntered() {
        setScreen(signedOut())

        composeTestRule.onNodeWithText("Sign in").assertIsNotEnabled()
        composeTestRule.onNodeWithTag(TAG_ACCOUNT_EMAIL).performClick().performTextInput("mary@example.com")
        composeTestRule.onNodeWithTag(TAG_ACCOUNT_PASSWORD).performClick().performTextInput("a long password")

        composeTestRule.onNodeWithText("Sign in").assertIsEnabled()
    }

    @Test
    fun signIn_sendsTheTrimmedEmailAndThePassword() {
        val calls = mutableListOf<Pair<String, String>>()
        setScreen(signedOut(), onSignIn = { email, password -> calls += email to password })

        composeTestRule.onNodeWithTag(TAG_ACCOUNT_EMAIL).performClick().performTextInput("  mary@example.com ")
        composeTestRule.onNodeWithTag(TAG_ACCOUNT_PASSWORD).performClick().performTextInput("a long password")
        composeTestRule.onNodeWithText("Sign in").performClick()

        assertEquals(listOf("mary@example.com" to "a long password"), calls)
    }

    @Test
    fun deleteAnyway_asksBeforeRemovingAnotherDevicesChange() {
        val decisions = mutableListOf<Decision>()
        setScreen(signedInWith(deleteOfEdited()), onResolve = { _, decision -> decisions += decision })

        composeTestRule.onNodeWithText("Delete anyway").performClick()
        composeTestRule.onNodeWithText("Delete for every device?").assertIsDisplayed()
        assertEquals(emptyList<Decision>(), decisions)

        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<Decision>(), decisions)

        composeTestRule.onNodeWithText("Delete anyway").performClick()
        composeTestRule.onNodeWithText("Delete").performClick()
        assertEquals(listOf(Decision.DELETE_ANYWAY), decisions)
    }

    @Test
    fun discardEdits_asksBeforeDroppingTheUnsentChange() {
        val decisions = mutableListOf<Decision>()
        setScreen(signedInWith(editOfDeleted()), onResolve = { _, decision -> decisions += decision })

        composeTestRule.onNodeWithText("Discard the change").performClick()
        composeTestRule.onNodeWithText("Discard your change?").assertIsDisplayed()
        assertEquals(emptyList<Decision>(), decisions)

        composeTestRule.onNodeWithText("Discard").performClick()
        assertEquals(listOf(Decision.DISCARD_EDITS), decisions)
    }

    @Test
    fun restoreEdits_isNotDestructive_soItRunsWithoutAConfirmation() {
        val decisions = mutableListOf<Decision>()
        setScreen(signedInWith(editOfDeleted()), onResolve = { _, decision -> decisions += decision })

        composeTestRule.onNodeWithText("Restore it").performClick()

        assertEquals(listOf(Decision.RESTORE_EDITS), decisions)
    }

    @Test
    fun busy_showsProgress_andHoldsSyncNow() {
        setScreen(signedInWith(idleSync()).copy(busy = true))

        composeTestRule.onNodeWithTag(TAG_ACCOUNT_BUSY).assertIsDisplayed()
        composeTestRule.onNodeWithText("Sync now").assertIsNotEnabled()
    }

    @Test
    fun syncPhase_isAnnouncedAsALiveRegion() {
        setScreen(signedInWith(SyncState(SyncPhase.WAITING_FOR_NETWORK, pendingChanges = 0, lastSyncedAtMillis = null)))

        composeTestRule.onNodeWithTag(TAG_ACCOUNT_PHASE)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun everyClickableControl_hasASpokenLabel_whenSignedOut() {
        setScreen(signedOut())

        composeTestRule.assertEveryClickableIsLabelled()
    }

    @Test
    fun everyClickableControl_hasASpokenLabel_whenAConflictIsHeld() {
        setScreen(signedInWith(deleteOfEdited()))

        composeTestRule.assertEveryClickableIsLabelled()
    }

    @Test
    fun largeText_keepsTheSignInButtonReachable() {
        setScreen(signedOut(), fontScale = 2f)

        composeTestRule.onNodeWithText("Sign in").performScrollTo().assertIsDisplayed()
    }

    private fun setScreen(
        state: AccountUiState,
        onSignIn: (String, String) -> Unit = { _, _ -> },
        onResolve: (Conflict, Decision) -> Unit = { _, _ -> },
        fontScale: Float = 1f,
    ) {
        composeTestRule.setContent {
            WithFontScale(fontScale) {
                RoutinePilotTheme {
                    AccountScreen(
                        state = state,
                        onBack = {},
                        onSignIn = onSignIn,
                        onRegister = { _, _ -> },
                        onSignOut = {},
                        onSyncNow = {},
                        onResolve = onResolve,
                    )
                }
            }
        }
    }

    private fun signedOut() = AccountUiState(email = null, sync = idleSync(), message = null, busy = false)

    private fun signedInWith(sync: SyncState) =
        AccountUiState(email = "mary@example.com", sync = sync, message = null, busy = false)

    private fun idleSync() = SyncState(SyncPhase.UP_TO_DATE, pendingChanges = 0, lastSyncedAtMillis = null)

    private fun signedInWith(conflict: Conflict) = signedInWith(
        SyncState(
            phase = SyncPhase.NEEDS_ATTENTION,
            pendingChanges = 1,
            lastSyncedAtMillis = null,
            heldConflicts = listOf(conflict),
        ),
    )

    private fun deleteOfEdited(): Conflict = Conflict.DeleteOfEdited(
        key = ObjectKey(SyncType.ROUTINE, UUID.fromString(ID)),
        mutationId = UUID.fromString(ID),
        remoteVersion = 2,
    )

    private fun editOfDeleted(): Conflict =
        Conflict.EditOfDeleted(ObjectKey(SyncType.ROUTINE, UUID.fromString(ID)), UUID.fromString(ID))

    private companion object {
        const val ID = "00000000-0000-4000-8000-0000000000d1"
    }
}
