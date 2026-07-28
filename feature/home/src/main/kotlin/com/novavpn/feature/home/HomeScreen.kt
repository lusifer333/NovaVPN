package com.novavpn.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.novavpn.domain.model.ConnectionState
import com.novavpn.ui.components.NovaTopBar
import com.novavpn.ui.components.StatCard
import com.novavpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToServers: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    val context = LocalContext.current

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // Permission granted — retry connection
            viewModel.retryConnect()
        } else {
            // Permission denied
            viewModel.onVpnPermissionDenied()
        }
    }

    Scaffold(
        topBar = {
            NovaTopBar(title = "NovaVPN")
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // Connection status card
            val displayServer = state.currentServer ?: state.selectedServer
            ConnectionCard(
                state = state.connectionState,
                serverName = displayServer?.let {
                    "${it.name} (${it.protocol.displayName})"
                } ?: "No server selected",
                serverAddress = displayServer?.let { "${it.address}:${it.port}" } ?: "",
                isLoading = state.isLoading,
                errorMessage = state.errorMessage,
                onConnect = {
                    // Check VPN permission before connecting
                    val intent = android.net.VpnService.prepare(context)
                    if (intent != null) {
                        vpnPermissionLauncher.launch(intent)
                    } else if (state.currentServer != null) {
                        viewModel.connect(state.currentServer!!)
                    } else {
                        viewModel.connectToSelected()
                    }
                },
                onDisconnect = { viewModel.disconnect() }
            )

            Spacer(Modifier.height(16.dp))

            // Selected server card
            if (state.selectedServer != null && state.currentServer == null) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Ready: ${state.selectedServer!!.name}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    label = "Download",
                    value = formatBytes(state.stats.bytesReceived),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Upload",
                    value = formatBytes(state.stats.bytesSent),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Duration",
                    value = formatDuration(state.stats.sessionDuration),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(24.dp))

            // Quick actions
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AssistChip(
                    onClick = onNavigateToServers,
                    label = { Text("Select Server") },
                    leadingIcon = { Icon(Icons.Default.Hub, contentDescription = null, Modifier.size(18.dp)) },
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = { viewModel.autoConnectToBest() },
                    label = { Text("Auto Connect") },
                    leadingIcon = { Icon(Icons.Default.Timeline, contentDescription = null, Modifier.size(18.dp)) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.weight(1f))

            // Server list summary
            if (state.serverList.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Available Servers",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${state.serverList.size} servers ready",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    serverName: String,
    serverAddress: String,
    isLoading: Boolean,
    errorMessage: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = state == ConnectionState.Connected
    val isError = state == ConnectionState.Error
    val showDisconnect = isConnected || isError
    val buttonColor by animateColorAsState(
        targetValue = if (showDisconnect) StatusError else MaterialTheme.colorScheme.primary,
        label = "buttonColor"
    )
    val buttonIcon = if (showDisconnect) Icons.Default.Stop else Icons.Default.PlayArrow
    val buttonLabel = if (showDisconnect) "Disconnect" else "Connect"

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status indicator
            val statusColor = when (state) {
                ConnectionState.Connected -> StatusConnected
                ConnectionState.Connecting -> StatusConnecting
                ConnectionState.Error -> StatusError
                else -> StatusDisconnected
            }
            val statusText = when (state) {
                ConnectionState.Connected -> "Connected"
                ConnectionState.Connecting -> "Connecting…"
                ConnectionState.Disconnecting -> "Disconnecting…"
                ConnectionState.Disconnected -> "Disconnected"
                ConnectionState.Error -> "Connection Error"
            }

            // Large status indicator circle
            Surface(
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                color = statusColor.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.VpnLock else Icons.Default.VpnKey,
                        contentDescription = statusText,
                        tint = statusColor,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = serverName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (serverAddress.isNotBlank() && !isConnected) {
                Text(
                    text = serverAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))

            // Error message
            if (isError && errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = StatusError.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusError,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Connect/Disconnect button
            Button(
                onClick = if (showDisconnect) onDisconnect else onConnect,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = buttonIcon,
                    contentDescription = buttonLabel,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buttonLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) "%dh %dm".format(hours, minutes)
    else "%dm %ds".format(minutes, seconds)
}