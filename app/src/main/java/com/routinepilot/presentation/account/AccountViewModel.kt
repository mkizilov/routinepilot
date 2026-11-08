package com.routinepilot.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.sync.AccountSessions
import com.routinepilot.domain.sync.AuthProblem
import com.routinepilot.domain.sync.Conflict
import com.routinepilot.domain.sync.Decision
import com.routinepilot.domain.sync.DeleteAccountOutcome
import com.routinepilot.domain.sync.SignInOutcome
import com.routinepilot.domain.sync.SyncEngine
import com.routinepilot.domain.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountUiState(
    val email: String?,
    val sync: SyncState,
    val message: AccountMessage?,
    val busy: Boolean,
)

sealed interface AccountMessage {
    data object SignedIn : AccountMessage

    data object AccountDeleted : AccountMessage

    data class Blocked(
        val unsyncedChanges: Int,
    ) : AccountMessage

    data class Failed(
        val problem: AuthProblem,
    ) : AccountMessage

    data class SignedOut(
        val deviceRevoked: Boolean,
    ) : AccountMessage
}

private const val STOP_TIMEOUT_MS = 5_000L

@HiltViewModel
class AccountViewModel
    @Inject
    constructor(
        private val engine: SyncEngine,
        private val sessions: AccountSessions,
    ) : ViewModel() {
        private val message = MutableStateFlow<AccountMessage?>(null)
        private val busy = MutableStateFlow(false)

        private val sources =
            combine(engine.state, sessions.email, message, busy) { sync, email, shown, working ->
                AccountUiState(email = email, sync = sync, message = shown, busy = working)
            }

        val uiState: StateFlow<AccountUiState> =
            sources.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                AccountUiState(email = null, sync = engine.state.value, message = null, busy = false),
            )

        init {
            viewModelScope.launch { engine.refreshState() }
        }

        fun signIn(
            email: String,
            password: String,
        ) = perform {
            when (val outcome = sessions.signIn(email, password)) {
                SignInOutcome.SignedIn -> AccountMessage.SignedIn
                is SignInOutcome.Blocked -> AccountMessage.Blocked(outcome.unsyncedChanges)
                is SignInOutcome.Failed -> AccountMessage.Failed(outcome.problem)
            }
        }

        fun register(
            email: String,
            password: String,
        ) = perform {
            when (val outcome = sessions.register(email, password)) {
                SignInOutcome.SignedIn -> AccountMessage.SignedIn
                is SignInOutcome.Blocked -> AccountMessage.Blocked(outcome.unsyncedChanges)
                is SignInOutcome.Failed -> AccountMessage.Failed(outcome.problem)
            }
        }

        fun signOut() = perform { AccountMessage.SignedOut(sessions.signOut().deviceRevoked) }

        fun deleteAccount(password: String) =
            perform {
                when (val outcome = sessions.deleteAccount(password)) {
                    DeleteAccountOutcome.Deleted -> AccountMessage.AccountDeleted
                    is DeleteAccountOutcome.Failed -> AccountMessage.Failed(outcome.problem)
                }
            }

        fun syncNow() =
            perform {
                engine.runCycle()
                null
            }

        fun resolve(
            conflict: Conflict,
            decision: Decision,
        ) = perform {
            engine.resolve(conflict, decision)
            null
        }

        private fun perform(action: suspend () -> AccountMessage?) {
            viewModelScope.launch {
                busy.value = true
                message.value = action()
                engine.refreshState()
                busy.value = false
            }
        }
    }
