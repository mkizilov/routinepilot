package com.routinepilot.presentation.navigation

import androidx.compose.runtime.Composable
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
import java.util.UUID

internal const val HOME_ROUTE = "home"
private const val EDITOR_ROUTE = "editor"
private const val RUN_ROUTE = "run"

internal fun editorRoute(routineId: UUID?): String =
    if (routineId == null) EDITOR_ROUTE else "$EDITOR_ROUTE?$ROUTINE_ID_ARG=$routineId"

internal fun runRoute(routineId: UUID): String = "$RUN_ROUTE/$routineId"

@Composable
fun RoutinePilotNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HOME_ROUTE) {
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
            route = "$RUN_ROUTE/{$ROUTINE_ID_ARG}",
            arguments = listOf(navArgument(ROUTINE_ID_ARG) { type = NavType.StringType }),
        ) {
            RoutineRunRoute(onClose = { navController.popBackStack() })
        }
    }
}
