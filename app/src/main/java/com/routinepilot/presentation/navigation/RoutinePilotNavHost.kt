package com.routinepilot.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.routinepilot.presentation.home.HomeRoute

internal const val HOME_ROUTE = "home"

@Composable
fun RoutinePilotNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HOME_ROUTE) {
        composable(HOME_ROUTE) {
            HomeRoute()
        }
    }
}
