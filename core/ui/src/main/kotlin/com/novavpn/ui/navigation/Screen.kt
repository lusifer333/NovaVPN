package com.novavpn.ui.navigation

/**
 * Navigation routes for NovaVPN.
 */
sealed class Screen(val route: String, val title: String) {
    data object Home : Screen("home", "Home")
    data object Subscriptions : Screen("subscriptions", "Subscriptions")
    data object Servers : Screen("servers", "Servers")
    data object Statistics : Screen("statistics", "Statistics")
    data object Settings : Screen("settings", "Settings")
    data object Logs : Screen("logs", "Logs")
}

/** Bottom navigation items (visible in main nav bar). */
val bottomNavItems = listOf(
    Screen.Home,
    Screen.Subscriptions,
    Screen.Servers,
    Screen.Statistics,
    Screen.Settings
)
