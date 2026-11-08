package com.routinepilot.presentation.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routinepilot.R
import com.routinepilot.domain.planning.Blocker
import com.routinepilot.domain.planning.Placement
import com.routinepilot.domain.planning.PlanIssue
import com.routinepilot.domain.planning.TimeRange
import com.routinepilot.domain.repository.PlanningSettings
import com.routinepilot.domain.repository.PlanningTaskSetting
import com.routinepilot.presentation.common.CenteredContent
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

private const val STEP_MINUTES = 15
private const val DEFAULT_MINUTES = 30
private const val DEFAULT_PRIORITY = 3
private const val MAX_PRIORITY = 5
private const val MINUTES_PER_HOUR = 60
private const val MAX_HOUR = 23
private val CLOCK = Regex("""(\d{1,2}):(\d{2})""")
internal const val TAG_PLANNING_LOADING = "planning_loading"
private val DATE_STYLE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** A task being edited. [existing] is null for a task that is not planned yet. */
private data class TaskEdit(
    val taskId: UUID,
    val title: String,
    val existing: PlanningTaskSetting?,
)

@Composable
fun PlanningRoute(
    onBack: () -> Unit,
    viewModel: PlanningViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlanningScreen(
        state = state,
        onBack = onBack,
        onSaveSettings = viewModel::saveSettings,
        onSavePlanned = viewModel::savePlanned,
        onRemovePlanned = viewModel::removePlanned,
        onNudge = viewModel::nudge,
        onTogglePin = viewModel::togglePin,
        onApply = viewModel::apply,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanningScreen(
    state: PlanningUiState,
    onBack: () -> Unit,
    onSaveSettings: (PlanningSettings) -> Unit,
    onSavePlanned: (PlanningTaskSetting) -> Unit,
    onRemovePlanned: (UUID) -> Unit,
    onNudge: (UUID, Int) -> Unit,
    onTogglePin: (UUID) -> Unit,
    onApply: () -> Unit,
    onRefresh: () -> Unit,
) {
    var editingHours by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskEdit?>(null) }
    val titles =
        remember(state.planned, state.candidates) {
            state.planned.associate { it.setting.taskId to it.title } +
                state.candidates.associate { it.taskId to it.title }
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.planning_title)) },
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.settings == null) item { LoadingPlan() }
                item { Text(stringResource(R.string.planning_day, state.date.format(DATE_STYLE))) }
                item { Text(stringResource(R.string.planning_note)) }
                item { HoursSection(state.settings, onEdit = { editingHours = true }) }
                plannedSection(state, onEdit = { editing = it })
                item { SectionTitle(R.string.planning_recommended_header) }
                placementsSection(state, titles, onNudge, onTogglePin)
                issuesSection(state, titles)
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.planning_preview)) }
                        Button(
                            onClick = onApply,
                            enabled = state.result?.canApply == true && !state.busy,
                        ) { Text(stringResource(R.string.planning_apply)) }
                    }
                }
                state.message?.let { message -> item { Text(messageText(message)) } }
            }
        }
    }
    val settings = state.settings
    if (editingHours && settings != null) {
        WorkingHoursDialog(
            settings = settings,
            onSave = {
                onSaveSettings(it)
                editingHours = false
            },
            onDismiss = { editingHours = false },
        )
    }
    editing?.let { edit ->
        TaskPlanDialog(
            edit = edit,
            others = state.planned.filterNot { it.setting.taskId == edit.taskId },
            onSave = {
                onSavePlanned(it)
                editing = null
            },
            onRemove = {
                onRemovePlanned(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private fun LazyListScope.plannedSection(
    state: PlanningUiState,
    onEdit: (TaskEdit) -> Unit,
) {
    item { SectionTitle(R.string.planning_tasks_header) }
    if (state.planned.isEmpty()) item { Text(stringResource(R.string.planning_no_tasks)) }
    items(state.planned, key = { it.setting.taskId }) { row ->
        Column {
            Text(row.title)
            Text(taskSummary(row.setting))
            OutlinedButton(onClick = { onEdit(TaskEdit(row.setting.taskId, row.title, row.setting)) }) {
                Text(stringResource(R.string.planning_edit))
            }
        }
    }
    items(state.candidates, key = { it.taskId }) { candidate ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(candidate.title)
            OutlinedButton(onClick = { onEdit(TaskEdit(candidate.taskId, candidate.title, null)) }) {
                Text(stringResource(R.string.planning_add))
            }
        }
    }
}

private fun LazyListScope.placementsSection(
    state: PlanningUiState,
    titles: Map<UUID, String>,
    onNudge: (UUID, Int) -> Unit,
    onTogglePin: (UUID) -> Unit,
) {
    val placements = state.result?.placements.orEmpty()
    if (placements.isEmpty()) item { Text(stringResource(R.string.planning_nothing_placed)) }
    items(placements, key = { it.taskId }) { placement ->
        PlacementRow(titles[placement.taskId].orEmpty(), placement, onNudge, onTogglePin)
    }
}

private fun LazyListScope.issuesSection(
    state: PlanningUiState,
    titles: Map<UUID, String>,
) {
    val result = state.result ?: return
    if (result.unplaced.isNotEmpty()) {
        item { SectionTitle(R.string.planning_unplaced_header) }
        items(result.unplaced, key = { it.taskId }) { entry ->
            IssueList(titles[entry.taskId].orEmpty(), entry.issues, titles)
        }
    }
    if (result.overrideConflicts.isNotEmpty()) {
        item { SectionTitle(R.string.planning_conflicts_header) }
        items(result.overrideConflicts, key = { it.taskId }) { conflict ->
            IssueList(titles[conflict.taskId].orEmpty(), conflict.issues, titles)
        }
    }
    if (result.staleOverrides.isNotEmpty()) {
        item { Text(stringResource(R.string.planning_issue_stale)) }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(stringResource(text), modifier = Modifier.semantics { heading() })
}

@Composable
private fun LoadingPlan() {
    val label = stringResource(R.string.planning_loading)
    CircularProgressIndicator(Modifier.testTag(TAG_PLANNING_LOADING).semantics { contentDescription = label })
}

@Composable
private fun HoursSection(
    settings: PlanningSettings?,
    onEdit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val hours =
            settings?.let {
                stringResource(R.string.planning_hours, formatClock(it.workday.start), formatClock(it.workday.end))
            }
        Text(hours.orEmpty())
        OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.planning_edit_hours)) }
    }
}

@Composable
private fun PlacementRow(
    title: String,
    placement: Placement,
    onNudge: (UUID, Int) -> Unit,
    onTogglePin: (UUID) -> Unit,
) {
    Column {
        Text(title)
        val block =
            stringResource(
                R.string.planning_block,
                formatClock(placement.range.start),
                formatClock(placement.range.end),
            )
        Text(block)
        if (placement.pinned) Text(stringResource(R.string.planning_pinned))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onNudge(placement.taskId, -STEP_MINUTES) }) {
                Text(stringResource(R.string.planning_earlier))
            }
            OutlinedButton(onClick = { onNudge(placement.taskId, STEP_MINUTES) }) {
                Text(stringResource(R.string.planning_later))
            }
            OutlinedButton(onClick = { onTogglePin(placement.taskId) }) {
                Text(stringResource(if (placement.pinned) R.string.planning_unpin else R.string.planning_pin))
            }
        }
    }
}

@Composable
private fun IssueList(
    title: String,
    issues: List<PlanIssue>,
    titles: Map<UUID, String>,
) {
    Column {
        Text(title)
        issues.forEach { issue -> issueLines(issue, titles).forEach { Text(it) } }
    }
}

@Composable
private fun issueLines(
    issue: PlanIssue,
    titles: Map<UUID, String>,
): List<String> =
    when (issue) {
        PlanIssue.MisalignedStart -> listOf(stringResource(R.string.planning_issue_misaligned))
        PlanIssue.OutsideWorkday -> listOf(stringResource(R.string.planning_issue_outside))
        is PlanIssue.OverlapsBlocker ->
            listOf(stringResource(R.string.planning_issue_overlap, blockerLabels(issue.blockers)))
        is PlanIssue.PastDeadline ->
            listOf(stringResource(R.string.planning_issue_past_deadline, formatClock(issue.deadlineMinute)))
        is PlanIssue.PrerequisiteAfterStart ->
            listOf(stringResource(R.string.planning_issue_prerequisite, titleList(issue.prerequisites, titles)))
        is PlanIssue.WindowClosed ->
            listOf(
                stringResource(
                    R.string.planning_issue_window,
                    formatClock(issue.earliestStart),
                    formatClock(issue.latestFinish),
                ),
            )
        is PlanIssue.InsufficientTime -> insufficientLines(issue)
        is PlanIssue.DependencyUnplaced ->
            listOf(stringResource(R.string.planning_issue_waits, titleList(issue.prerequisites, titles)))
        is PlanIssue.CyclicDependency ->
            listOf(stringResource(R.string.planning_issue_cycle, titleList(issue.members, titles)))
    }

@Composable
private fun insufficientLines(issue: PlanIssue.InsufficientTime): List<String> {
    val summary =
        if (issue.freeMinutes >= issue.neededMinutes) {
            stringResource(R.string.planning_issue_fragmented, issue.neededMinutes, issue.largestFreeMinutes)
        } else {
            stringResource(R.string.planning_issue_insufficient, issue.neededMinutes, issue.freeMinutes)
        }
    if (issue.blockers.isEmpty()) return listOf(summary)
    return listOf(summary, stringResource(R.string.planning_issue_blocked_by, blockerLabels(issue.blockers)))
}

@Composable
private fun messageText(message: PlanningMessage): String =
    stringResource(
        when (message) {
            PlanningMessage.Applied -> R.string.planning_applied
            PlanningMessage.CannotApply -> R.string.planning_cannot_apply
            PlanningMessage.InvalidInput -> R.string.planning_invalid
        },
    )

@Composable
private fun WorkingHoursDialog(
    settings: PlanningSettings,
    onSave: (PlanningSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var start by remember { mutableStateOf(formatClock(settings.workday.start)) }
    var end by remember { mutableStateOf(formatClock(settings.workday.end)) }
    var breaks by remember {
        mutableStateOf(settings.breaks.joinToString(", ") { "${formatClock(it.start)}-${formatClock(it.end)}" })
    }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planning_hours_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text(stringResource(R.string.planning_start)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text(stringResource(R.string.planning_end)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = breaks,
                    onValueChange = { breaks = it },
                    label = { Text(stringResource(R.string.planning_breaks)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invalid) Text(stringResource(R.string.planning_invalid))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val from = parseClock(start)
                val to = parseClock(end)
                val parsedBreaks = parseBreaks(breaks)
                val window = if (from != null && to != null && from < to) TimeRange(from, to) else null
                if (window == null || parsedBreaks == null) {
                    invalid = true
                } else {
                    onSave(PlanningSettings(window, parsedBreaks))
                }
            }) { Text(stringResource(R.string.planning_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planning_cancel)) } },
    )
}

@Composable
private fun TaskPlanDialog(
    edit: TaskEdit,
    others: List<PlannedTaskRow>,
    onSave: (PlanningTaskSetting) -> Unit,
    onRemove: (UUID) -> Unit,
    onDismiss: () -> Unit,
) {
    val existing = edit.existing
    var minutes by remember { mutableStateOf((existing?.durationMinutes ?: DEFAULT_MINUTES).toString()) }
    var priority by remember { mutableStateOf((existing?.priority ?: DEFAULT_PRIORITY).toString()) }
    var deadline by remember { mutableStateOf(existing?.deadlineMinute?.let { formatClock(it) }.orEmpty()) }
    var after by remember { mutableStateOf(existing?.dependsOn.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(edit.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it },
                    label = { Text(stringResource(R.string.planning_duration)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it },
                    label = { Text(stringResource(R.string.planning_priority)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = deadline,
                    onValueChange = { deadline = it },
                    label = { Text(stringResource(R.string.planning_deadline)) },
                    singleLine = true,
                )
                others.forEach { other ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = other.setting.taskId in after,
                            onCheckedChange = { checked ->
                                after = if (checked) after + other.setting.taskId else after - other.setting.taskId
                            },
                        )
                        Text(stringResource(R.string.planning_after, other.title))
                    }
                }
                if (invalid) Text(stringResource(R.string.planning_invalid))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val duration = minutes.trim().toIntOrNull()?.takeIf { it > 0 }
                val level = priority.trim().toIntOrNull()?.takeIf { it in 1..MAX_PRIORITY }
                val due = if (deadline.isBlank()) null else parseClock(deadline)
                val deadlineInvalid = deadline.isNotBlank() && due == null
                if (duration == null || level == null || deadlineInvalid) {
                    invalid = true
                } else {
                    onSave(PlanningTaskSetting(edit.taskId, duration, level, due, after))
                }
            }) { Text(stringResource(R.string.planning_save)) }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = { onRemove(edit.taskId) }) { Text(stringResource(R.string.planning_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.planning_cancel)) }
            }
        },
    )
}

private fun taskSummary(setting: PlanningTaskSetting): String {
    val due = setting.deadlineMinute?.let { ", finish by ${formatClock(it)}" }.orEmpty()
    return "${setting.durationMinutes} min, priority ${setting.priority}$due"
}

private fun blockerLabels(blockers: List<Blocker>): String = blockers.joinToString { it.label }

private fun titleList(
    ids: List<UUID>,
    titles: Map<UUID, String>,
): String = ids.joinToString { titles[it].orEmpty() }

/** Minutes after midnight as "HH:mm". */
internal fun formatClock(minute: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", minute / MINUTES_PER_HOUR, minute % MINUTES_PER_HOUR)

/** Reads "HH:mm" in 24-hour time. Returns null when the text is not a valid time of day. */
internal fun parseClock(text: String): Int? {
    val match = CLOCK.matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    return if (hour in 0..MAX_HOUR && minute in 0 until MINUTES_PER_HOUR) hour * MINUTES_PER_HOUR + minute else null
}

/** Reads breaks as "HH:mm-HH:mm" separated by commas. Blank means no breaks. Null when any part is invalid. */
internal fun parseBreaks(text: String): List<TimeRange>? {
    if (text.isBlank()) return emptyList()
    val ranges = text.split(",").map { parseBreak(it) }
    return if (ranges.any { it == null }) null else ranges.filterNotNull()
}

/** One "HH:mm-HH:mm" range, or null when either end is not a time or the range does not run forward. */
private fun parseBreak(part: String): TimeRange? {
    val bounds = part.split("-")
    val start = bounds.getOrNull(0)?.let(::parseClock)
    val end = bounds.getOrNull(1)?.let(::parseClock)
    return when {
        bounds.size != 2 || start == null || end == null -> null
        start >= end -> null
        else -> TimeRange(start, end)
    }
}
