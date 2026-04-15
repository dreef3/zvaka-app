package com.dreef3.weightlossapp.app

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.media.ModelDownloadState
import com.dreef3.weightlossapp.app.network.NetworkConnectionType
import com.dreef3.weightlossapp.app.notifications.needsNotificationPermission
import com.dreef3.weightlossapp.chat.CoachModel
import com.dreef3.weightlossapp.chat.requiredModelDescriptor
import com.dreef3.weightlossapp.features.chat.CoachChatScreenRoute
import com.dreef3.weightlossapp.features.onboarding.LocalModelPreparationScreen
import com.dreef3.weightlossapp.features.onboarding.OnboardingScreenRoute
import com.dreef3.weightlossapp.features.onboarding.ProfileEditScreen
import com.dreef3.weightlossapp.features.summary.TodaySummaryScreenRoute
import com.dreef3.weightlossapp.features.trends.MealDebugScreenRoute
import com.dreef3.weightlossapp.features.trends.TrendsScreenRoute

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
)

private val BottomDestinations = listOf(
    BottomDestination(
        route = AppDestinations.Home,
        label = "Today",
        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
    ),
    BottomDestination(
        route = AppDestinations.Trends,
        label = "Trends",
        icon = { Icon(Icons.Outlined.AutoGraph, contentDescription = null) },
    ),
    BottomDestination(
        route = AppDestinations.Chat,
        label = "Coach",
        icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
    ),
    BottomDestination(
        route = AppDestinations.Profile,
        label = "Profile",
        icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
    ),
)

@Composable
fun AppNavHost(
    appStateViewModel: AppStateViewModel,
) {
    val navController = rememberNavController()
    val state by appStateViewModel.state.collectAsStateWithLifecycle()

    if (!state.isReady) {
        CircularProgressIndicator()
        return
    }

    val startDestination = if (state.isSetupComplete) AppDestinations.Home else AppDestinations.Onboarding
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = state.isSetupComplete && currentDestination?.route in BottomDestinations.map { it.route }
    fun navigateToTopLevel(route: String) {
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateToTopLevel(destination.route) },
                            icon = destination.icon,
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppDestinations.Onboarding) {
                OnboardingScreenRoute(
                    container = AppContainer.instance,
                    onCompleted = {
                        navController.navigate(AppDestinations.Home) {
                            popUpTo(AppDestinations.Onboarding) { inclusive = true }
                        }
                    },
                )
            }
            composable(AppDestinations.Home) {
                TodaySummaryScreenRoute(
                    container = AppContainer.instance,
                    onNavigateToTrends = { navigateToTopLevel(AppDestinations.Trends) },
                    onOpenHistoricalChat = { sessionId ->
                        navController.navigate(AppDestinations.historicalChat(sessionId))
                    },
                    onOpenMealDebug = { entryId ->
                        navController.navigate(AppDestinations.mealDebug(entryId))
                    },
                )
            }
            composable(AppDestinations.Trends) {
                TrendsScreenRoute(
                    container = AppContainer.instance,
                    onOpenHistoricalChat = { sessionId ->
                        navController.navigate(AppDestinations.historicalChat(sessionId))
                    },
                    onOpenMealDebug = { entryId ->
                        navController.navigate(AppDestinations.mealDebug(entryId))
                    },
                )
            }
            composable(AppDestinations.Chat) {
                val context = LocalContext.current
                val activity = context as? Activity
                val container = AppContainer.instance
                val coachModel by container.preferences.coachModel.collectAsStateWithLifecycle(initialValue = CoachModel.Gemma)
                val coachDescriptor = coachModel.requiredModelDescriptor()
                val downloadState by container.modelDownloadRepository
                    .observeState(coachDescriptor)
                    .collectAsStateWithLifecycle(initialValue = ModelDownloadState())
                val coachReady = container.modelStorage.hasUsableModel(coachDescriptor)
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { container.modelDownloadRepository.enqueueIfNeeded(coachDescriptor) }
                fun requestDownload() {
                    if (activity != null && needsNotificationPermission(context)) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        container.modelDownloadRepository.enqueueIfNeeded(coachDescriptor)
                    }
                }
                LaunchedEffect(coachDescriptor.fileName, coachReady, downloadState.isDownloading) {
                    if (!coachReady &&
                        !downloadState.isDownloading &&
                        container.networkConnectionMonitor.currentConnectionType() == NetworkConnectionType.Wifi
                    ) {
                        requestDownload()
                    }
                }
                if (coachReady) {
                    CoachChatScreenRoute(
                        container = container,
                    )
                } else {
                    LocalModelPreparationScreen(
                        state = downloadState,
                        modifier = Modifier.padding(24.dp),
                        showRetry = !downloadState.isDownloading,
                        onRetry = ::requestDownload,
                    )
                }
            }
            composable(
                route = "${AppDestinations.ChatHistory}/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) { backStack ->
                val sessionId = backStack.arguments?.getLong("sessionId") ?: return@composable
                CoachChatScreenRoute(
                    container = AppContainer.instance,
                    sessionId = sessionId,
                    readOnly = true,
                )
            }
            composable(
                route = "${AppDestinations.MealDebug}/{entryId}",
                arguments = listOf(navArgument("entryId") { type = NavType.LongType }),
            ) { backStack ->
                val entryId = backStack.arguments?.getLong("entryId") ?: return@composable
                MealDebugScreenRoute(
                    container = AppContainer.instance,
                    entryId = entryId,
                    onRetry = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(AppDestinations.Profile) {
                ProfileEditScreen(
                    container = AppContainer.instance,
                    onBack = { navController.popBackStack() },
                    onResetToOnboarding = {
                        navController.navigate(AppDestinations.Onboarding) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                    onRestoreCompleted = { isSetupComplete ->
                        navController.navigate(
                            if (isSetupComplete) AppDestinations.Home else AppDestinations.Onboarding,
                        ) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                inclusive = !isSetupComplete
                            }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
