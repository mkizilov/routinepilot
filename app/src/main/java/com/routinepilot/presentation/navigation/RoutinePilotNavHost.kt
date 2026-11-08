package com.routinepilot.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.routinepilot.presentation.home.HomeNavigation
import com.routinepilot.presentation.home.HomeRoute
import com.routinepilot.presentation.routine.editor.ROUTINE_ID_ARG
import com.routinepilot.presentation.routine.editor.RoutineEditorRoute
import com.routinepilot.presentation.routine.run.RoutineRunRoute
import com.routinepilot.presentation.schedule.AgendaRoute
import com.routinepilot.presentation.schedule.SCHEDULE_ID_ARG
import com.routinepilot.presentation.schedule.ScheduleEditorRoute
import com.routinepilot.presentation.schedule.ScheduleListRoute
import com.routinepilot.presentation.stats.StatsRoute
import java.util.UUID

internal const val AGENDA_ROUTE = "agenda"
internal const val HOME_ROUTE = "home"
private const val EDITOR_ROUTE = "editor"
private const val RUN_ROUTE = "run"
private const val RESUME_ARG = "resume"
private const val SCHEDULES_ROUTE = "schedules"
private const val SCHEDULE_EDITOR_ROUTE = "schedule"
private const val STATS_ROUTE = "stats"

/** A request from a notification tap. [resume] is true when the user pressed Resume on a paused run. */
data class RunRequest(val routineId: UUID, val resume: Boolean)

internal fun editorRoute(routineId: UUID?): String =
    if (routineId == null) EDITOR_ROUTE else "$EDITOR_ROUTE?$ROUTINE_ID_ARG=$routineId"

internal fun runRoute(routineId: UUID, resume: Boolean = false): String =
    "$RUN_ROUTE/$routineId?$RESUME_ARG=$resume"

internal fun scheduleEditorRoute(scheduleId: UUID?): String =
    if (scheduleId == null) SCHEDULE_EDITOR_ROUTE else "$SCHEDULE_EDITOR_ROUTE?$SCHEDULE_ID_ARG=$scheduleId"

@Composable
fun RoutinePilotNavHost(
    runRequest: RunRequest? = null,
    onRunRequestHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = AGENDA_ROUTE) {
        composable(AGENDA_ROUTE) {
            AgendaRoute(
                onNewSchedule = { navController.navigate(scheduleEditorRoute(null)) },
                onOpenSchedules = { navController.navigate(SCHEDULES_ROUTE) },
                onOpenProgress = { navController.navigate(STATS_ROUTE) },
            )
        }
        composable(STATS_ROUTE) {
            StatsRoute(onBack = { navController.popBackStack() })
        }
        composable(HOME_ROUTE) {
            HomeRoute(
                navigation = HomeNavigation(
                    onEdit = { navController.navigate(editorRoute(it)) },
                    onRun = { navController.navigate(runRoute(it)) },
                    onNew = { navController.navigate(editorRoute(null)) },
                ),
            )
        }
        composable(
            route = "$EDITOR_ROUTE?$ROUTINE_ID_ARG={$ROUTINE_ID_ARG}",
            arguments = listOf(
                navArgument(ROUTINE_ID_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            RoutineEditorRoute(onClose = { navController.popBackStack() })
        }
        composable(
            route = "$RUN_ROUTE/{$ROUTINE_ID_ARG}?$RESUME_ARG={$RESUME_ARG}",
            arguments = listOf(
                navArgument(ROUTINE_ID_ARG) { type = NavType.StringType },
                navArgument(RESUME_ARG) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            RoutineRunRoute(
                onClose = { navController.popBackStack() },
                autoResume = entry.arguments?.getBoolean(RESUME_ARG) ?: false,
            )
        }
        composable(SCHEDULES_ROUTE) {
            ScheduleListRoute(
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(scheduleEditorRoute(it)) },
                onNew = { navController.navigate(scheduleEditorRoute(null)) },
            )
        }
        composable(
            route = "$SCHEDULE_EDITOR_ROUTE?$SCHEDULE_ID_ARG={$SCHEDULE_ID_ARG}",
            arguments = listOf(
                navArgument(SCHEDULE_ID_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            ScheduleEditorRoute(onClose = { navController.popBackStack() })
        }
    }

    // Declared after NavHost so the graph is set before the first navigation.
    LaunchedEffect(runRequest) {
        val request = runRequest ?: return@LaunchedEffect
        navController.navigate(runRoute(request.routineId, request.resume)) { launchSingleTop = true }
        onRunRequestHandled()
    }
}
