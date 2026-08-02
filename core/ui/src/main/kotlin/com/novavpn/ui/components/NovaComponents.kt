package com.novavpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import com.novavpn.domain.model.Subscription
import com.novavpn.domain.model.VpnState
import com.novavpn.ui.theme.*

/**
 * Application top bar with title and optional actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaTopBar(
    title: String,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold
            )
        },
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * Status indicator showing VPN connection state with color.
 */
@Composable
fun NovaStatusIndicator(
    state: VpnState,
    modifier: Modifier = Modifier
) {
    val color = when (state) {
        is VpnState.Connected -> StatusConnected
        is VpnState.Connecting -> StatusConnecting
        is VpnState.Disconnected -> StatusDisconnected
        is VpnState.Disconnecting -> StatusConnecting
        is VpnState.Error -> StatusError
    }
    val label = when (state) {
        is VpnState.Connected -> "Connected"
        is VpnState.Connecting -> "Connecting…"
        is VpnState.Disconnected -> "Disconnected"
        is VpnState.Disconnecting -> "Disconnecting…"
        is VpnState.Error -> "Error"
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.1f),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

/**
 * Health indicator dot.
 */
@Composable
fun HealthIndicator(
    isHealthy: Boolean?,
    modifier: Modifier = Modifier
) {
    val color = when (isHealthy) {
        true -> StatusHealthy
        false -> StatusUnhealthy
        null -> StatusUntested
    }
    val label = when (isHealthy) {
        true -> "Healthy"
        false -> "Unhealthy"
        null -> "Untested"
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * Server list item card.
 */
@Composable
fun ServerListItem(
    server: ServerConfig,
    isSelected: Boolean,
    latencyMs: Long? = null,
    isHealthy: Boolean? = null,
    probeResult: ServerProbeResult? = null,
    onTap: () -> Unit,
    onProbe: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showProbeButton: Boolean = false
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Protocol icon
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (server.protocol.name.lowercase()) {
                            "vmess" -> Icons.Default.Lock
                            "vless" -> Icons.Default.LockOpen
                            "trojan" -> Icons.Default.Shield
                            "shadowsocks" -> Icons.Default.Security
                            else -> Icons.Default.Hub
                        },
                        contentDescription = server.protocol.displayName,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${server.protocol.displayName} • ${server.address}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))

            // Latency + health. When the probe button is shown it already
            // encodes state (Untested/🚀<ms>/🚀 ✗), so suppress the separate
            // HealthIndicator to avoid a duplicate 'Untested' label (P2).
            Column(horizontalAlignment = Alignment.End) {
                if (latencyMs != null && latencyMs >= 0) {
                    Text(
                        text = "${latencyMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!showProbeButton) {
                    HealthIndicator(isHealthy = isHealthy)
                }
            }
            // Single per-server probe button: 'Untested' until probed, then
            // 🚀<ms>. Wired only when showProbe is true so it never renders for
            // callers that don't support the flow (avoids the duplicate badge).
            if (showProbeButton && onProbe != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onProbe,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text(
                        text = when {
                            probeResult?.e2eMs != null ->
                                if (probeResult.e2eOk) "🚀 ${probeResult.e2eMs}ms" else "🚀 ✗"
                            probeResult != null -> "🚀 ✗"
                            else -> "Untested"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            probeResult == null -> StatusUntested
                            probeResult.e2eOk -> StatusHealthy
                            else -> StatusUnhealthy
                        }
                    )
                }
            }
}
        }
    }
}

/**
 * Subscription list item.
 */
@Composable
fun SubscriptionListItem(
    subscription: Subscription,
    serverCount: Int = 0,
    onTap: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(16.dp)
        ) {
            Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subscription.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "$serverCount servers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = subscription.isEnabled,
                onCheckedChange = onToggle
            )
        }
        }
    }
}

/**
 * Stat card for displaying a value with label.
 */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * EmojiIcon used as avatar.
 */
@Composable
fun ProtocolIcon(
    protocol: String,
    modifier: Modifier = Modifier
) {
    val icon = when (protocol.lowercase()) {
        "vmess" -> Icons.Default.Lock
        "vless" -> Icons.Default.LockOpen
        "trojan" -> Icons.Default.Shield
        "shadowsocks" -> Icons.Default.Security
        "socks5" -> Icons.Default.Hub
        "http" -> Icons.Default.Public
        else -> Icons.Default.Dns
    }
    Surface(
        modifier = modifier.size(36.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = protocol,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
