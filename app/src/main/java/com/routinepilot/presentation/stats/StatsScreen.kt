package com.routinepilot.presentation.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routinepilot.R
import com.routinepilot.domain.statistics.DayStats
import com.routinepilot.domain.statistics.DayStatus
import com.routinepilot.domain.statistics.PeriodStats
import com.routinepilot.domain.statistics.RoutineConsistency
import com.routinepilot.domain.statistics.Statistics
import com.routinepilot.presentation.routine.formatDuration
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal const val TAG_STATS_TODAY_PERCENT = "stats_today_percent"
internal const val TAG_STATS_CURRENT_STREAK = "stats_current_streak"

private const val MILLIS_PER_SECOND = 1_000L
private val WEEK_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("d", Locale.getDefault())
private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())

@Composable
fun StatsRoute(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatsScreen(state = state, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    state: StatsUiState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val stats = state.statistics
        if (stats == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.stats_loading))
            }
        } else {
            StatsContent(stats, state.routineTitles, Modifier.fillMaxSize().padding(innerPadding))
        }
    }
}

@Composable
private fun StatsContent(
    stats: Statistics,
    titles: Map<UUID, String>,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { TodayCard(stats.today) }
        item { StreakCard(stats.currentStreak, stats.longestStreak) }
        item {
            Section(R.string.stats_last_7_days) { PeriodRow(stats.last7Days) }
        }
        item {
            Section(R.string.stats_weekly) {
                TrendBars(stats.weeks.map { it.completionPercent to WEEK_LABEL.format(it.start) })
            }
        }
        item {
            Section(R.string.stats_monthly) {
                TrendBars(stats.months.map { it.completionPercent to MONTH_LABEL.format(it.start) })
            }
        }
        item {
            Section(R.string.stats_calendar) {
                CalendarGrid(stats.calendar)
            }
        }
        item {
            Section(R.string.stats_consistency) {
                ConsistencyList(stats.consistency, titles)
            }
        }
        item {
            Text(
                stringResource(R.string.stats_definitions),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Section(
    titleRes: Int,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun TodayCard(day: DayStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.stats_today), style = MaterialTheme.typography.titleMedium)
            Text(
                day.completionPercent?.let { stringResource(R.string.stats_percent, it) }
                    ?: stringResource(R.string.stats_nothing_due),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.testTag(TAG_STATS_TODAY_PERCENT),
            )
            Text(statusText(day.status), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.stats_sessions_count, day.finishedSessions))
                Text(stringResource(R.string.stats_steps_count, day.completedSteps))
                Text(stringResource(R.string.stats_focus_time, formatFocus(day.focusedMillis)))
            }
        }
    }
}

@Composable
private fun StreakCard(
    current: Int,
    longest: Int,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.stats_streak_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text(
                        stringResource(R.string.stats_days, current),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.testTag(TAG_STATS_CURRENT_STREAK),
                    )
                    Text(stringResource(R.string.stats_streak_current), style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    Text(stringResource(R.string.stats_days, longest), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.stats_streak_longest), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.stats_streak_explain), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PeriodRow(period: PeriodStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            period.completionPercent?.let { stringResource(R.string.stats_percent, it) }
                ?: stringResource(R.string.stats_nothing_due),
        )
        Text(stringResource(R.string.stats_sessions_count, period.finishedSessions))
        Text(stringResource(R.string.stats_focus_time, formatFocus(period.focusedMillis)))
    }
}

/** One bar per period. An empty track with no bar means nothing was due in that period. */
@Composable
private fun TrendBars(bars: List<Pair<Int?, String>>) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / bars.size
            val barWidth = slot * 0.6f
            bars.forEachIndexed { index, (percent, _) ->
                val left = index * slot + (slot - barWidth) / 2f
                drawRect(trackColor, Offset(left, 0f), Size(barWidth, size.height))
                if (percent != null) {
                    val height = size.height * percent / 100f
                    drawRect(barColor, Offset(left, size.height - height), Size(barWidth, height))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { (_, label) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Five weeks, Monday first. Future days are blank. */
@Composable
private fun CalendarGrid(days: List<DayStats>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day -> CalendarCell(day) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(DayStatus.KEPT, R.string.stats_legend_kept)
            LegendItem(DayStatus.EXCUSED, R.string.stats_legend_excused)
            LegendItem(DayStatus.BROKEN, R.string.stats_legend_missed)
            LegendItem(DayStatus.IN_PROGRESS, R.string.stats_legend_pending)
        }
    }
}

@Composable
private fun CalendarCell(day: DayStats) {
    val color = statusColor(day.status)
    Box(
        Modifier
            .size(36.dp)
            .background(color, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (day.status != DayStatus.FUTURE) {
            Text(
                day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun LegendItem(
    status: DayStatus,
    labelRes: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(statusColor(status), RoundedCornerShape(2.dp)))
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ConsistencyList(
    rows: List<RoutineConsistency>,
    titles: Map<UUID, String>,
) {
    if (rows.isEmpty()) {
        Text(stringResource(R.string.stats_consistency_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.sortedBy { titles[it.routineId].orEmpty() }.forEach { row ->
            Column {
                Row {
                    Text(titles[row.routineId].orEmpty(), modifier = Modifier.weight(1f))
                    Text(
                        row.percent?.let { stringResource(R.string.stats_percent, it) }
                            ?: stringResource(R.string.stats_nothing_due),
                    )
                }
                row.percent?.let { percent ->
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun statusText(status: DayStatus): String =
    when (status) {
        DayStatus.KEPT -> stringResource(R.string.stats_status_kept)
        DayStatus.EXCUSED -> stringResource(R.string.stats_status_excused)
        DayStatus.BROKEN -> stringResource(R.string.stats_status_missed)
        DayStatus.IN_PROGRESS -> stringResource(R.string.stats_status_in_progress)
        DayStatus.FUTURE -> ""
    }

@Composable
private fun statusColor(status: DayStatus) =
    when (status) {
        DayStatus.KEPT -> MaterialTheme.colorScheme.primary
        DayStatus.EXCUSED -> MaterialTheme.colorScheme.tertiary
        DayStatus.BROKEN -> MaterialTheme.colorScheme.error
        DayStatus.IN_PROGRESS -> MaterialTheme.colorScheme.outline
        DayStatus.FUTURE -> MaterialTheme.colorScheme.surface
    }

private fun formatFocus(millis: Long): String = formatDuration((millis / MILLIS_PER_SECOND).toInt())
