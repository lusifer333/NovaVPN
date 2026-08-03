package com.novavpn.feature.servers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.novavpn.domain.probe.KaringTestUrls
import com.novavpn.ui.components.NovaTopBar
import com.novavpn.ui.theme.StatusError
import com.novavpn.ui.theme.StatusHealthy

/**
 * Karing-style config-test screen:
 * - pick the reachability URL (Karing's 6 defaults),
 * - pick the per-attempt timeout (1–15s, Karing's url_test_timeout range),
 * - "Test All" probes every selectable server with the REAL connect settings
 *   and the chosen URL, streaming live results sorted by latency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestConfigScreen(
    onNavigateBack: () -> Unit,
    onServerSelected: () -> Unit,
    viewModel: TestConfigViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Navigate back to Home once a tested server has been selected/connected.
    LaunchedEffect(Unit) {
        viewModel.onServerSelected = onServerSelected
    }

    Scaffold(
        topBar = {
            NovaTopBar(
                title = "Test Configs",
                onNavigateBack = onNavigateBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Test URL picker ──
            item {
                Text(
                    text = "Test URL",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(KaringTestUrls.all) { url ->
                val selected = state.selectedUrl == url
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.selectUrl(url) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { viewModel.selectUrl(url) }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── Timeout ──
            item {
                Text(
                    text = "Timeout: ${state.timeoutSec}s (1–15)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = state.timeoutSec.toFloat(),
                    onValueChange = { viewModel.setTimeout(it.toInt()) },
                    valueRange = 1f..15f,
                    steps = 13
                )
            }

            // ── Test All / Stop ──
            item {
                Button(
                    onClick = { if (state.isTesting) viewModel.stopTest() else viewModel.testAll() },
                    enabled = !state.isTesting || state.isTesting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (state.isTesting) {
                        ButtonDefaults.buttonColors(containerColor = StatusError)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Icon(
                        imageVector = if (state.isTesting) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isTesting) "Stop Test" else "Test All")
                }
                if (state.isTesting || state.testedCount > 0) {
                    Text(
                        text = "${state.testedCount} / ${state.totalCount} tested",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // ── Live results (fastest first, tap = connect) ──
            val sorted = state.results.values.sortedBy {
                if (it.ok) it.e2eMs ?: Long.MAX_VALUE else Long.MAX_VALUE
            }
            items(sorted, key = { it.server.id }) { row ->
                TestResultRowItem(row, onClick = { viewModel.selectServer(row.server) })
            }
        }
    }
}

@Composable
private fun TestResultRowItem(
    row: TestResultRow,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status dot
            Surface(
                modifier = Modifier.size(10.dp),
                shape = MaterialTheme.shapes.small,
                color = if (row.ok) StatusHealthy else StatusError
            ) {}
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.server.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = "${row.server.address}:${row.server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (row.ok) "🚀 ${row.e2eMs}ms" else "✗",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (row.ok) StatusHealthy else StatusError
            )
        }
    }
}
