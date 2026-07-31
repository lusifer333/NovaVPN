package com.novavpn.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.ThemeMode
import com.novavpn.ui.components.NovaTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToLogs: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val settings = state.settings

    Scaffold(
        topBar = {
            NovaTopBar(title = "Settings")
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Connection section
            SettingsSectionHeader("Connection")

            EngineSelector(
                selected = settings.selectedEngine,
                onSelect = { viewModel.setEngine(it) }
            )

            SettingsSwitchItem(
                title = "Auto Connect",
                subtitle = "Automatically connect to best server",
                checked = settings.enableAutoConnect,
                onCheckedChange = { viewModel.setAutoConnect(it) }
            )

            SettingsSwitchItem(
                title = "Always-On VPN",
                subtitle = "Keep VPN active when possible",
                checked = settings.enableAlwaysOnVpn,
                onCheckedChange = { viewModel.setAlwaysOn(it) }
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // Network section
            SettingsSectionHeader("Network")

            OutlinedTextField(
                value = settings.customDns,
                onValueChange = { viewModel.setCustomDns(it) },
                label = { Text("Custom DNS") },
                placeholder = { Text("e.g. 8.8.8.8") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            SettingsSwitchItem(
                title = "IPv6",
                subtitle = "Enable IPv6 support",
                checked = settings.enableIPv6,
                onCheckedChange = { viewModel.setIPv6(it) }
            )

            SettingsSwitchItem(
                title = "FakeDNS",
                subtitle = "Use fake DNS for better routing",
                checked = settings.enableFakeDns,
                onCheckedChange = { viewModel.setFakeDns(it) }
            )

            SettingsSwitchItem(
                title = "Block QUIC",
                subtitle = "Fix browser SSL errors by forcing TCP fallback",
                checked = settings.enableBlockQuic,
                onCheckedChange = { viewModel.setBlockQuic(it) }
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // Privacy section
            SettingsSectionHeader("Apps")

            SettingsSwitchItem(
                title = "Per-App VPN",
                subtitle = "Select which apps use VPN",
                checked = settings.enablePerAppVpn,
                onCheckedChange = { viewModel.setPerAppVpn(it) }
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // General section
            SettingsSectionHeader("General")

            ThemeSelector(
                selected = settings.theme,
                onSelect = { viewModel.setTheme(it) }
            )

            SettingsSwitchItem(
                title = "Notifications",
                subtitle = "Show VPN status notifications",
                checked = settings.enableNotifications,
                onCheckedChange = { viewModel.setNotifications(it) }
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // Logs
            SettingsSectionHeader("Diagnostics")

            Card(
                onClick = onNavigateToLogs,
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("View Logs", fontWeight = FontWeight.Medium)
                        Text("Copy, export, search logs", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }

            Spacer(Modifier.height(32.dp))

            // App info
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "NovaVPN v1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Built with ❤️",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun EngineSelector(
    selected: EngineType,
    onSelect: (EngineType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("VPN Engine", fontWeight = FontWeight.Medium)
                Text(selected.displayName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.UnfoldMore, contentDescription = "Select")
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EngineType.entries.filter { it != EngineType.Unknown }.forEach { engine ->
                DropdownMenuItem(
                    text = { Text(engine.displayName) },
                    onClick = {
                        onSelect(engine)
                        expanded = false
                    },
                    trailingIcon = {
                        if (engine == selected) {
                            Icon(Icons.Default.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ThemeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Theme", fontWeight = FontWeight.Medium)
                Text(selected.name, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.UnfoldMore, contentDescription = "Select")
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { Text(theme.name) },
                    onClick = {
                        onSelect(theme)
                        expanded = false
                    },
                    trailingIcon = {
                        if (theme == selected) {
                            Icon(Icons.Default.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        }
    }
}
