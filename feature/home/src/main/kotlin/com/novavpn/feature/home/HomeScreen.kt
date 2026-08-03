package com.novavpn.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.novavpn.domain.model.VpnState
import com.novavpn.ui.components.NovaTopBar
import com.novavpn.ui.components.StatCard
import com.novavpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToServers: () -> Unit,
    onNavigateToLogs: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    val context = LocalContext.current

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.retryConnect()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    Scaffold(
        topBar = {
            NovaTopBar(
                title = "NovaVPN",
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Default.Terminal, contentDescription = "Logs")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // Connection status card
            val displayServer = state.currentServer ?: state.selectedServer
            ConnectionCard(
                vpnState = state.vpnState,
                serverName = displayServer?.let {
                    "${it.name} (${it.protocol.displayName})"
                } ?: "No server selected",
                serverAddress = displayServer?.let { "${it.address}:${it.port}" } ?: "",
                activeBadges = state.activeBadges,
                isLoading = state.isLoading,
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

            Spacer(Modifier.height(16.dp))

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

/**
 * Connection status card.
 *
 * Button logic is derived from a single sealed [VpnState] — there is
 * never a mismatch between header text and button label:
 *
 * | State           | Header          | Button          |
 * |-----------------|-----------------|-----------------|
 * | Disconnected    | Disconnected    | Connect         |
 * | Connecting      | Connecting…     | Cancel          |
 * | Connected       | Connected       | Disconnect      |
 * | Disconnecting   | Disconnecting…  | Disconnect      |
 * | Error(msg)      | {msg}           | Connect (retry) |
 */
@Composable
private fun ConnectionCard(
    vpnState: VpnState,
    serverName: String,
    serverAddress: String,
    activeBadges: List<ConfigBadge>,
    isLoading: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    // ── Derived state (single source: vpnState) ──
    val isConnected = vpnState == VpnState.Connected
    val isDisconnected = vpnState == VpnState.Disconnected
    val isConnecting = vpnState == VpnState.Connecting
    val isDisconnecting = vpnState == VpnState.Disconnecting
    val isError = vpnState is VpnState.Error

    // Button = "Disconnect" only when Connected or Disconnecting
    val showDisconnect = isConnected || isDisconnecting
    // Button = "Cancel" when Connecting
    val showCancel = isConnecting
    // Button = "Connect" when Disconnected or Error (retry)
    val showConnect = isDisconnected || isError

    val buttonLabel = when {
        showDisconnect -> "Disconnect"
        showCancel -> "Cancel"
        else -> "Connect"
    }
    val buttonIcon = when {
        showDisconnect -> Icons.Default.Stop
        showCancel -> Icons.Default.Close
        else -> Icons.Default.PlayArrow
    }
    val buttonColor by animateColorAsState(
        targetValue = when {
            showDisconnect -> StatusError
            showCancel -> StatusConnecting
            else -> MaterialTheme.colorScheme.primary
        },
        label = "buttonColor"
    )

    // Status text
    val statusText = when (vpnState) {
        is VpnState.Connected -> "Connected"
        is VpnState.Connecting -> "Connecting…"
        is VpnState.Disconnecting -> "Disconnecting…"
        is VpnState.Disconnected -> "Disconnected"
        is VpnState.Error -> "Connection Error"
    }

    // Status color
    val statusColor = when (vpnState) {
        is VpnState.Connected -> StatusConnected
        is VpnState.Connecting -> StatusConnecting
        is VpnState.Error -> StatusError
        else -> StatusDisconnected
    }

    // Error message — embedded in the sealed type, no separate field
    val errorMessage = (vpnState as? VpnState.Error)?.message

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
            // Status indicator circle
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

            // Active config-parameter badges — minimal single-letter circles
            // (F = TLS Fragment, Q = Block QUIC, K = TCP Keep-Alive, D = FakeDNS).
            if (activeBadges.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    activeBadges.forEach { badge ->
                        Surface(
                            modifier = Modifier.size(26.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = badge.letter,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            if (serverAddress.isNotBlank() && !isConnected) {
                Text(
                    text = serverAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))

            // Error message — only when VpnState.Error
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

            // Connect / Cancel / Disconnect button
            Button(
                onClick = when {
                    showDisconnect || showCancel -> onDisconnect
                    else -> onConnect
                },
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
