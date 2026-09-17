package xyz.desent.presentation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.settings.component.SectionHeader
import xyz.desent.presentation.ui.settings.component.SliderSetting
import xyz.desent.presentation.ui.settings.component.SwitchSetting
import xyz.desent.presentation.ui.settings.viewmodel.BlendPreset
import xyz.desent.presentation.ui.settings.viewmodel.SpamPolicyViewModel
import kotlin.math.abs

private const val MINUTE_MS = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamPolicyScreen(
    onNavigateBack: () -> Unit,
    viewModel: SpamPolicyViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spam filter") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            val config = state.config
            val active = config.enabled

            // --- Master toggle ---
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    SwitchSetting(
                        title = "Spam filter",
                        description = "Quarantine messages that look like spam.",
                        checked = config.enabled,
                        onCheckedChange = viewModel::setEnabled
                    )
                }
            }

            // --- Detection layers ---
            SectionHeader("Detection layers")
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SwitchSetting(
                        title = "Heuristics",
                        description = "Known spam patterns, failed sender checks.",
                        checked = config.layerHeuristicsEnabled,
                        onCheckedChange = viewModel::setLayerHeuristics,
                        enabled = active
                    )
                    HorizontalDivider()
                    SwitchSetting(
                        title = "Blocklist",
                        description = "Curated blocked domains and senders.",
                        checked = config.layerBlocklistEnabled,
                        onCheckedChange = viewModel::setLayerBlocklist,
                        enabled = active
                    )
                    HorizontalDivider()
                    SwitchSetting(
                        title = "Bayesian learning",
                        description = "Learns from your “Mark as spam” / “Not spam”.",
                        checked = config.layerBayesianEnabled,
                        onCheckedChange = viewModel::setLayerBayesian,
                        enabled = active
                    )
                }
            }

            // --- Sensitivity (inverted threshold) ---
            SectionHeader("Sensitivity")
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    SliderSetting(
                        title = "Filter sensitivity",
                        description = "Higher catches more mail as spam (stricter).",
                        value = (10.0 - config.threshold).toFloat().coerceIn(0f, 10f),
                        onValueChange = viewModel::setSensitivity,
                        valueRange = 0f..10f,
                        valueFormat = { String.format("%.1f", it) },
                        enabled = active
                    )
                }
            }

            // --- Blend presets ---
            SectionHeader("Heuristics ↔ learning balance")
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        text = "Emphasise rules or what the filter has learned from you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        BlendPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = isPresetActive(config.heuristicWeight, config.bayesianWeight, preset),
                                onClick = { viewModel.applyBlendPreset(preset) },
                                label = { Text(presetLabel(preset)) },
                                enabled = active,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // --- Cross-device sync ---
            SectionHeader("Cross-device sync")
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        text = "Your filter is synced privately across your devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Last synced ${relativeTime(state.lastDeviceSyncAt * 1000L)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = viewModel::syncNow,
                        enabled = !state.syncing,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        if (state.syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Sync now")
                        }
                    }
                }
            }

            // --- Blocklist + learned stats ---
            SectionHeader("Blocklist & training")
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    val m = state.manifest
                    Text(
                        text = if (m.version > 0) "Curated list v${m.version}" else "Curated list (version unknown)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${m.blockedDomains.size} blocked domains · ${m.blockedSenders.size} blocked senders",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Updated ${relativeTime(state.lastBlocklistSyncAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = viewModel::refreshBlocklist,
                        enabled = !state.refreshingBlocklist,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.refreshingBlocklist) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Refresh blocklist")
                        }
                    }
                    HorizontalDivider()
                    Text(
                        text = "${state.tokenCount} tokens learned",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Grows as you mark messages as spam or not spam.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun isPresetActive(h: Double, b: Double, preset: BlendPreset): Boolean =
    abs(h - preset.heuristicWeight) < 0.01 && abs(b - preset.bayesianWeight) < 0.01

private fun presetLabel(preset: BlendPreset): String = when (preset) {
    BlendPreset.BALANCED -> "Balanced"
    BlendPreset.HEURISTICS -> "Rules"
    BlendPreset.BAYESIAN -> "Learned"
}

private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "never"
    return android.text.format.DateUtils
        .getRelativeTimeSpanString(epochMillis, System.currentTimeMillis(), MINUTE_MS)
        .toString()
}
