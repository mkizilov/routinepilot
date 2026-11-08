package com.routinepilot.presentation.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routinepilot.R
import com.routinepilot.domain.sync.AuthProblem
import com.routinepilot.domain.sync.Conflict
import com.routinepilot.domain.sync.Decision
import com.routinepilot.domain.sync.SyncPhase

@Composable
fun AccountRoute(
    onBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AccountScreen(
        state = state,
        onBack = onBack,
        onSignIn = viewModel::signIn,
        onRegister = viewModel::register,
        onSignOut = viewModel::signOut,
        onSyncNow = viewModel::syncNow,
        onResolve = viewModel::resolve,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    state: AccountUiState,
    onBack: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
    onResolve: (Conflict, Decision) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { Text(messageText(it)) }
            val email = state.email
            if (email == null) {
                SignInForm(busy = state.busy, onSignIn = onSignIn, onRegister = onRegister)
            } else {
                SignedInSection(
                    email = email,
                    state = state,
                    onSyncNow = onSyncNow,
                    onSignOut = onSignOut,
                    onResolve = onResolve,
                )
            }
            Text(stringResource(R.string.account_local_note))
        }
    }
}

@Composable
private fun SignInForm(busy: Boolean, onSignIn: (String, String) -> Unit, onRegister: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val ready = !busy && email.isNotBlank() && password.isNotEmpty()
    Text(stringResource(R.string.account_signed_out_note))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(R.string.account_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.account_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSignIn(email.trim(), password) }, enabled = ready) {
        Text(stringResource(R.string.account_sign_in))
    }
    OutlinedButton(onClick = { onRegister(email.trim(), password) }, enabled = ready) {
        Text(stringResource(R.string.account_create))
    }
}

@Composable
private fun SignedInSection(
    email: String,
    state: AccountUiState,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onResolve: (Conflict, Decision) -> Unit,
) {
    Text(stringResource(R.string.account_signed_in_as, email))
    Text(phaseText(state.sync.phase))
    if (state.sync.pendingChanges > 0) {
        Text(stringResource(R.string.account_pending, state.sync.pendingChanges))
    }
    if (state.sync.rejectedChanges > 0) {
        Text(stringResource(R.string.account_rejected, state.sync.rejectedChanges))
    }
    state.sync.heldConflicts.forEach { conflict -> HeldConflict(conflict, onResolve) }
    Button(onClick = onSyncNow, enabled = !state.busy) { Text(stringResource(R.string.account_sync_now)) }
    OutlinedButton(onClick = onSignOut, enabled = !state.busy) { Text(stringResource(R.string.account_sign_out)) }
}

@Composable
private fun HeldConflict(conflict: Conflict, onResolve: (Conflict, Decision) -> Unit) {
    when (conflict) {
        is Conflict.DeleteOfEdited -> {
            Text(stringResource(R.string.account_conflict_delete_edited))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onResolve(conflict, Decision.KEEP_EDIT) }) {
                    Text(stringResource(R.string.account_keep_edit))
                }
                OutlinedButton(onClick = { onResolve(conflict, Decision.DELETE_ANYWAY) }) {
                    Text(stringResource(R.string.account_delete_anyway))
                }
            }
        }
        is Conflict.EditOfDeleted -> {
            Text(stringResource(R.string.account_conflict_edit_deleted))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onResolve(conflict, Decision.RESTORE_EDITS) }) {
                    Text(stringResource(R.string.account_restore_edits))
                }
                OutlinedButton(onClick = { onResolve(conflict, Decision.DISCARD_EDITS) }) {
                    Text(stringResource(R.string.account_discard_edits))
                }
            }
        }
        is Conflict.Superseded -> Unit
    }
}

@Composable
private fun phaseText(phase: SyncPhase): String = stringResource(
    when (phase) {
        SyncPhase.NOT_SIGNED_IN -> R.string.account_phase_not_signed_in
        SyncPhase.SYNCING -> R.string.account_phase_syncing
        SyncPhase.UP_TO_DATE -> R.string.account_phase_up_to_date
        SyncPhase.WAITING_FOR_NETWORK -> R.string.account_phase_waiting
        SyncPhase.NEEDS_SIGN_IN -> R.string.account_phase_needs_sign_in
        SyncPhase.NEEDS_ATTENTION -> R.string.account_phase_attention
    },
)

@Composable
private fun messageText(message: AccountMessage): String = when (message) {
    AccountMessage.SignedIn -> stringResource(R.string.account_message_signed_in)
    is AccountMessage.Blocked -> stringResource(R.string.account_message_blocked, message.unsyncedChanges)
    is AccountMessage.SignedOut -> stringResource(
        if (message.deviceRevoked) {
            R.string.account_message_signed_out
        } else {
            R.string.account_message_signed_out_device_kept
        },
    )
    is AccountMessage.Failed -> stringResource(
        when (message.problem) {
            AuthProblem.INVALID_CREDENTIALS -> R.string.account_message_invalid_credentials
            AuthProblem.EMAIL_TAKEN -> R.string.account_message_email_taken
            AuthProblem.INVALID_INPUT -> R.string.account_message_invalid_input
            AuthProblem.UNAVAILABLE -> R.string.account_message_unavailable
            AuthProblem.SERVER -> R.string.account_message_server
        },
    )
}
