package com.novavpn.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.novavpn.feature.home.HomeScreen
import com.novavpn.feature.subscriptions.SubscriptionsScreen
import com.novavpn.feature.servers.ProfilesScreen
import com.novavpn.feature.servers.TestConfigScreen
import com.novavpn.feature.statistics.StatisticsScreen
import com.novavpn.feature.settings.SettingsScreen
import com.novavpn.feature.logs.LogsScreen
import com.novavpn.ui.navigation.Screen

private data class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector,
    val label: String
)

private val bottomNavItems = listOf(
    BottomNavItem(Screen.Home, Icons.Default.Home, "Home"),
    BottomNavItem(Screen.Subscriptions, Icons.Default.Dns, "Subscriptions"),
    BottomNavItem(Screen.Servers, Icons.Default.Hub, "Profiles"),
    BottomNavItem(Screen.Statistics, Icons.Default.BarChart, "Stats"),
    BottomNavItem(Screen.Settings, Icons.Default.Settings, "Settings")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = bottomNavItems.any { item ->
        currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.screen.route
                        } == true

                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToServers = {
                        navController.navigate(Screen.Servers.route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToLogs = {
                        navController.navigate(Screen.Logs.route)
                    },
                    onNavigateToTestConfigs = {
                        navController.navigate(Screen.TestConfig.route)
                    }
                )
            }

            composable(Screen.Subscriptions.route) {
                SubscriptionsScreen()
            }

            composable(Screen.Servers.route) {
                ProfilesScreen()
            }

            composable(Screen.Statistics.route) {
                StatisticsScreen()
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToLogs = {
                        navController.navigate(Screen.Logs.route)
                    }
                )
            }

            composable(Screen.Logs.route) {
                LogsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.TestConfig.route) {
                TestConfigScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onServerSelected = { navController.popBackStack() }
                )
            }
        }
    }
}
