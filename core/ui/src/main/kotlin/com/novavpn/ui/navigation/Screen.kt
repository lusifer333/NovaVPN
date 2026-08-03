package com.novavpn.ui.navigation

/** Navigation routes for NovaVPN. */
sealed class Screen(val route: String, val title: String) {
    data object Home : Screen("home", "Home")
    data object Subscriptions : Screen("subscriptions", "Subscriptions")
    data object Servers : Screen("servers", "Servers")
    data object Statistics : Screen("statistics", "Statistics")
    data object Settings : Screen("settings", "Settings")
    data object Logs : Screen("logs", "Logs")
    data object TestConfig : Screen("test_config", "Test Configs")
}
