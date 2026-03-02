package com.example.napguard.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.napguard.ui.alarm.AlarmScreen
import com.example.napguard.ui.dashboard.DashboardScreen
import com.example.napguard.ui.monitoring.MonitoringScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val MONITORING = "monitoring"
    const val ALARM = "alarm"
}

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.DASHBOARD,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onStartMonitoring = { navController.navigate(Routes.MONITORING) }
            )
        }
        composable(Routes.MONITORING) {
            MonitoringScreen(
                onAbort = {
                    navController.popBackStack(Routes.DASHBOARD, inclusive = false)
                },
                onAlarmTriggered = {
                    navController.navigate(Routes.ALARM) {
                        popUpTo(Routes.DASHBOARD)
                    }
                }
            )
        }
        composable(Routes.ALARM) {
            AlarmScreen(
                onDismiss = {
                    navController.popBackStack(Routes.DASHBOARD, inclusive = false)
                }
            )
        }
    }
}
