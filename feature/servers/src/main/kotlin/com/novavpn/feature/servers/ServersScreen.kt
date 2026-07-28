package com.novavpn.feature.servers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.novavpn.domain.model.ServerConfig
import com.novavpn.ui.components.NovaTopBar
import com.novavpn.ui.components.ServerListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    viewModel: ServersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var searchExpanded by remember { mutableStateOf(false) }

    // Pre-compute filtered list — only recalculates when searchQuery or servers change
    val filteredServers by remember {
        derivedStateOf {
            val query = state.searchQuery.trim().lowercase()
            if (query.isBlank()) state.servers
            else state.servers.filter {
                it.name.lowercase().contains(query) ||
                it.address.lowercase().contains(query) ||
                it.protocol.displayName.lowercase().contains(query)
            }
        }
    }

    Scaffold(
        topBar = {
            NovaTopBar(
                title = "Servers",
                actions = {
                    IconButton(onClick = { searchExpanded = !searchExpanded }) {
                        Icon(
                            if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            if (searchExpanded) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChange(it) },
                    placeholder = { Text("Search servers...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (filteredServers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (state.searchQuery.isNotBlank())
                                "No servers match your search"
                            else if (state.servers.isEmpty())
                                "No servers available\nAdd a subscription first"
                            else
                                "All servers are hidden\n(enable subscriptions first)",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredServers,
                        key = { it.id }
                    ) { server ->
                        ServerListItem(
                            server = server,
                            isSelected = server.id == state.selectedServerId,
                            onTap = { viewModel.selectServer(server) }
                        )
                    }
                }
            }
        }
    }
}