package com.routinepilot.presentation.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.routinepilot.R
import com.routinepilot.presentation.common.CenteredContent

/** The app's help, one short section per topic. Each heading is marked for screen readers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        CenteredContent(innerPadding) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(HelpTopic.entries, key = { it.name }) { topic ->
                    Column {
                        Text(
                            stringResource(topic.titleRes),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(stringResource(topic.bodyRes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** The help topics, in the order they appear. */
private enum class HelpTopic(val titleRes: Int, val bodyRes: Int) {
    ROUTINES(R.string.help_routines_title, R.string.help_routines_body),
    RUNNING(R.string.help_running_title, R.string.help_running_body),
    SCHEDULES(R.string.help_schedules_title, R.string.help_schedules_body),
    PLANNING(R.string.help_planning_title, R.string.help_planning_body),
    PROGRESS(R.string.help_progress_title, R.string.help_progress_body),
    SYNC(R.string.help_sync_title, R.string.help_sync_body),
    PRIVACY(R.string.help_privacy_title, R.string.help_privacy_body),
}
