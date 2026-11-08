package com.routinepilot.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.routinepilot.R
import com.routinepilot.domain.onboarding.OnboardingPage
import com.routinepilot.domain.onboarding.nextPage
import com.routinepilot.presentation.common.CenteredContent

@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    OnboardingScreen(
        onFinished = {
            viewModel.finish()
            onFinished()
        },
    )
}

/** A short first-run introduction. Skip is available on every page, and nothing here needs an account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pages = OnboardingPage.entries
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index]
    val last = nextPage(page) == null

    fun advance() {
        if (last) onFinished() else index += 1
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
                actions = {
                    TextButton(onClick = onFinished) { Text(stringResource(R.string.onboarding_skip)) }
                },
            )
        },
    ) { innerPadding ->
        CenteredContent(innerPadding) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.onboarding_step, index + 1, pages.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(page.titleRes),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(page.bodyRes), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { index -= 1 }, enabled = index > 0) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                    Button(onClick = { advance() }) {
                        Text(
                            stringResource(
                                if (last) R.string.onboarding_get_started else R.string.onboarding_next,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private val OnboardingPage.titleRes: Int
    get() =
        when (this) {
            OnboardingPage.WELCOME -> R.string.onboarding_welcome_title
            OnboardingPage.ROUTINES -> R.string.onboarding_routines_title
            OnboardingPage.PRIVACY -> R.string.onboarding_privacy_title
        }

private val OnboardingPage.bodyRes: Int
    get() =
        when (this) {
            OnboardingPage.WELCOME -> R.string.onboarding_welcome_body
            OnboardingPage.ROUTINES -> R.string.onboarding_routines_body
            OnboardingPage.PRIVACY -> R.string.onboarding_privacy_body
        }
