package com.novavpn.feature.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.novavpn.domain.model.ServerConfig
import com.novavpn.ui.components.NovaTopBar
import com.novavpn.ui.components.ServerListItem

/**
 * Profiles (پروفایلها) screen.
 *
 * - ⛏ Mine section on top: the curated healthy reservoir + two buttons —
 *   «Test All Servers» (display-only for now) and «Fill Mine» (real).
 * - Below: every subscription is one profile; menus are COLLAPSED by
 *   default and expand on tap, revealing that subscription's configs with
 *   their probe badges (⚡ TCP, 🔒 TLS, 🚀 real delay) on a separate row —
 *   results NEVER overlap the config name.
 * - NO auto-test on open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { NovaTopBar(title = "Profiles") }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "mine") {
                MineSection(
                    state = state,
                    onFillMine = viewModel::fillMine,
                    onStopFill = viewModel::stopFill,
                    onSelect = viewModel::selectServer,
                    onProbe = viewModel::probeServer
                )
            }

            if (state.profiles.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "No profiles yet\nAdd a subscription first",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                // Flattened lazy layout: each profile card is one item, and
                // an expanded profile's configs are emitted as their own
                // lazy rows. The old version rendered 2000+ rows in a single
                // non-lazy Column inside one card item — it composed all of
                // them at once and froze the UI on expand.
                state.profiles.forEach { profile ->
                    items(
                        items = listOf(profile),
                        key = { "profile-${it.subscription.id}" }
                    ) { p ->
                        ProfileCard(
                            profile = p,
                            onToggle = { viewModel.toggleProfile(p.subscription.id) }
                        )
                    }
                    if (profile.isExpanded) {
                        items(
                            items = profile.servers,
                            key = { "srv-${it.id}" }
                        ) { server ->
                            ServerListItem(
                                server = server,
                                isSelected = server.id == state.selectedServerId,
                                probeResult = state.results[server.id],
                                onTap = { viewModel.selectServer(server) },
                                onProbe = { viewModel.probeServer(server) },
                                showProbeButton = true,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** ⛏ Mine section — curated healthy servers + fill controls. */
@Composable
private fun MineSection(
    state: ProfilesUiState,
    onFillMine: () -> Unit,
    onStopFill: () -> Unit,
    onSelect: (ServerConfig) -> Unit,
    onProbe: (ServerConfig) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⛏ Mine",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${state.mine.size}/${state.mineCapacity}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Display-only for now — real behaviour comes later.
                OutlinedButton(onClick = { }) {
                    Text("Test All Servers")
                }
                Button(
                    onClick = if (state.isFilling) onStopFill else onFillMine,
                    colors = if (state.isFilling) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    if (state.isFilling) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    } else {
                        Text("Fill Mine")
                    }
                }
            }

            if (state.mine.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                state.mine.forEach { server ->
                    ServerListItem(
                        server = server,
                        isSelected = server.id == state.selectedServerId,
                        probeResult = state.results[server.id],
                        onTap = { onSelect(server) },
                        onProbe = { onProbe(server) },
                        showProbeButton = true,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/** One subscription — collapsed by default, expands to its configs. */
@Composable
private fun ProfileCard(
    profile: ProfileUi,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (profile.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.subscription.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${profile.servers.size} configs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
