package com.novavpn.feature.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.novavpn.domain.model.LogEntry
import com.novavpn.domain.model.LogLevel
import com.novavpn.ui.components.NovaTopBar
import com.novavpn.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val serviceScope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) } // 0=Diagnostics, 1=Program Logs

    Scaffold(
        topBar = {
            NovaTopBar(
                title = "Logs",
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(onClick = { viewModel.addTestLog() }) {
                        Icon(Icons.Default.BugReport, contentDescription = "Test Log")
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                    }
                    val clipboard = LocalClipboardManager.current
                    IconButton(onClick = {
                        serviceScope.launch {
                            val text = if (selectedTab == 0) viewModel.copyLogs()
                                else state.rawLogText
                            clipboard.setText(AnnotatedString(text))
                        }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
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
            // Tab buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("Diagnostics") },
                    leadingIcon = {
                        Icon(Icons.Default.Monitor, contentDescription = null, Modifier.size(18.dp))
                    }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("Program Logs") },
                    leadingIcon = {
                        Icon(Icons.Default.Terminal, contentDescription = null, Modifier.size(18.dp))
                    }
                )
            }

            when (selectedTab) {
                0 -> DiagnosticsTab(state)
                1 -> ProgramLogsTab(state)
            }
        }
    }
}

@Composable
private fun DiagnosticsTab(state: LogsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Diagnostics card — latest DIAG lines
        val diagEntries = state.entries.filter { it.tag == "NovaVpnService" && it.message.contains("DIAG") }
        val lastDiag = diagEntries.lastOrNull()
        if (lastDiag != null) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Monitor, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("TUN Diagnostics",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    val parts = lastDiag.message.split(", ")
                    parts.forEach { part ->
                        val color = when {
                            part.contains("rx=0") && part.contains("tx=0") -> StatusConnecting
                            part.contains("fdAlive=false") -> StatusError
                            part.contains("fdAlive=true") -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(part, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, color = color)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Config validation result
        val configEntries = state.entries.filter { it.message.contains("Config validation") }
        val lastConfig = configEntries.lastOrNull()
        if (lastConfig != null) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Config Validation",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(lastConfig.message.take(300),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 10, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Detailed log list
        Text("Recent Logs",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 4.dp))

        val listState = rememberLazyListState()
        if (state.entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                // Deduplicate by stable id before rendering
                val deduped = state.entries
                    .takeLast(500)
                    .distinctBy { it.id }
                    .takeLast(200)
                items(items = deduped, key = { it.id }) { entry ->
                    LogEntryItem(entry)
                }
            }
        }
    }
}

@Composable
private fun ProgramLogsTab(state: LogsUiState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (state.rawLogText.isBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = state.rawLogText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.Debug -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.Info -> MaterialTheme.colorScheme.primary
        LogLevel.Warning -> StatusConnecting
        LogLevel.Error -> StatusError
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(entry.level.name.take(4),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            color = levelColor, modifier = Modifier.width(32.dp))
        Text(entry.tag,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(56.dp))
        Text(entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
    }
}
