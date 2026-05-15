package com.beyond.nvr.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.beyond.nvr.ui.cameras.CamerasScreen
import com.beyond.nvr.ui.dashboard.DashboardScreen
import com.beyond.nvr.ui.liveview.LiveViewScreen
import com.beyond.nvr.ui.login.LoginScreen
import com.beyond.nvr.ui.recordings.RecordingDetailScreen
import com.beyond.nvr.ui.recordings.RecordingsScreen
import com.beyond.nvr.ui.settings.SettingsScreen
import com.beyond.nvr.ui.stats.StatsScreen
import com.beyond.nvr.data.repository.PreferencesRepository
import org.koin.compose.koinInject

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val prefsRepo: PreferencesRepository = koinInject()
    val credentials by prefsRepo.observeCredentials().collectAsState(initial = null)

    val isAuthenticated = credentials != null
    val startDestination = if (isAuthenticated) Routes.Dashboard.route else Routes.Login.route

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.Dashboard.route) {
                        popUpTo(Routes.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.Dashboard.route) {
            DashboardScreen(
                onNavigateToCameras = { navController.navigate(Routes.Cameras.route) },
                onNavigateToRecordings = { navController.navigate(Routes.Recordings.route) },
                onNavigateToLiveView = { cameraId ->
                    navController.navigate(Routes.LiveView.createRoute(cameraId))
                },
                onNavigateToStats = { navController.navigate(Routes.Stats.route) },
                onNavigateToSettings = { navController.navigate(Routes.Settings.route) },
                onLogout = {
                    navController.navigate(Routes.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.Cameras.route) {
            CamerasScreen(
                onBack = { navController.popBackStack() },
                onCameraClick = { cameraId ->
                    navController.navigate(Routes.LiveView.createRoute(cameraId))
                },
            )
        }

        composable(
            route = Routes.LiveView.route,
            arguments = listOf(navArgument("cameraId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val cameraId = backStackEntry.arguments?.getString("cameraId") ?: return@composable
            LiveViewScreen(
                cameraId = cameraId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.Recordings.route) {
            RecordingsScreen(
                onBack = { navController.popBackStack() },
                onRecordingClick = { recordingId ->
                    navController.navigate(Routes.RecordingDetail.createRoute(recordingId))
                },
            )
        }

        composable(
            route = Routes.RecordingDetail.route,
            arguments = listOf(navArgument("recordingId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val recordingId = backStackEntry.arguments?.getString("recordingId") ?: return@composable
            RecordingDetailScreen(
                recordingId = recordingId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.Stats.route) {
            StatsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
