package xyz.desent.presentation.ui.storage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.STORAGE_CATEGORY_ORDER
import xyz.desent.domain.model.StorageBreakdown
import xyz.desent.domain.model.StorageCategory
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.AccountBarTitle
import xyz.desent.presentation.ui.components.DesentLogoMark
import xyz.desent.presentation.ui.storage.viewmodel.StorageViewModel
import java.util.concurrent.TimeUnit

/**
 * Storage breakdown screen — shows the user's unified storage quota and where
 * the bytes are going, split by category. See refs/STORAGE_TAB_ANDROID.md.
 *
 * Fetches on open via [StorageViewModel]; the refresh icon re-fetches. There is
 * no WebSocket push for quota and no timer (battery); an explicit refresh and
 * the on-open fetch cover the common case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onNavigateToBilling: () -> Unit = {},
    viewModel: StorageViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { AccountBarTitle() },
                navigationIcon = {
                    DesentLogoMark()
                },
                actions = {
                    TextButton(onClick = onNavigateToBilling) {
                        Text("Pricing")
                    }
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !uiState.isRefreshing
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> CenteredProgress()
                uiState.error != null -> ErrorRow(
                    message = uiState.error!!,
                    onRetry = { viewModel.refresh() },
                    isStale = false
                )
                uiState.breakdown != null -> StorageList(
                    breakdown = uiState.breakdown!!,
                    isStale = uiState.isStale,
                    onRefresh = { viewModel.refresh() }
                )
                else -> ErrorRow(
                    message = "Couldn't load storage. Tap to retry.",
                    onRetry = { viewModel.refresh() },
                    isStale = false
                )
            }
        }
    }
}

@Composable
private fun StorageList(
    breakdown: StorageBreakdown,
    isStale: Boolean,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lg)
    ) {
        item { Spacer(Modifier.height(Spacing.lg)) }
        item { HeroBar(breakdown = breakdown) }
        item { Spacer(Modifier.height(Spacing.lg)) }

        // Always render every known key, even if the server omitted some.
        // Sum-mismatch (spec §8): when SUM(categories) != used the category
        // percentages switch to a `used` denominator until the next refresh.
        val denominator = if (breakdown.categorySumMismatch) breakdown.used else breakdown.cap
        val rows = breakdown.categories.withMissingKeys()
        items(rows, key = { it.key }) { category ->
            CategoryRow(category = category, denominator = denominator)
            Spacer(Modifier.height(Spacing.md))
        }

        item { Spacer(Modifier.height(Spacing.sm)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Last refreshed ${relativeTime(breakdown.refreshedAt)}" +
                        (if (isStale) " · showing cached data" else "") +
                        " · tap to refresh",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isStale) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onRefresh() }
                )
            }
        }
        item { Spacer(Modifier.height(Spacing.lg)) }
    }
}

@Composable
private fun HeroBar(breakdown: StorageBreakdown) {
    val pct = breakdown.fraction
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatBytesBinary(breakdown.used)} used of ${formatBytesBinary(breakdown.cap)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        if (pct != null) {
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = progressColorFor(pct),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "${"%.1f".format(pct * 100)}% full",
                style = MaterialTheme.typography.bodySmall,
                color = progressColorFor(pct)
            )
        } else {
            // cap <= 0 — show absolute counts only, no bars.
            Text(
                text = "No storage cap set",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategoryRow(category: StorageCategory, denominator: Long) {
    // Same denominator as the hero bar (cap), so the category bars visually
    // stack to the hero total — NOT bytes / max_category. On a sum mismatch
    // the caller swaps the denominator to `used` (spec §8) so the rows still
    // add up visually.
    val pct = if (denominator > 0) {
        (category.bytes.toFloat() / denominator).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = category.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${formatBytesBinary(category.bytes)} · ${"%.1f".format(pct * 100)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        LinearProgressIndicator(
            progress = { pct },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = progressColorFor(pct),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun CenteredProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit, isStale: Boolean) {
    Surface(
        modifier = Modifier.fillMaxSize().clickable { onRetry() },
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = if (isStale) "$message\n\nShowing cached data — tap to refresh." else message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Amber ≥80%, red ≥100%, otherwise the theme primary — matches the thresholds
 * in refs/STORAGE_TAB_ANDROID.md §5.
 */
@Composable
private fun progressColorFor(pct: Float): Color = when {
    pct >= 1.0f -> Color(0xFFD32F2F)
    pct >= 0.8f -> Color(0xFFFFA000)
    else -> MaterialTheme.colorScheme.primary
}

/**
 * Human-readable byte size using binary units (KiB/MiB/GiB/TiB), matching the
 * spec's formatBytes helper. Local to this screen — the shared
 * `formatBytes` in QuotaComponents.kt uses KB/MB/GB and is left untouched.
 */
private fun formatBytesBinary(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var v = bytes.toDouble() / 1024.0
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    return "%.1f %s".format(v, units[i])
}

private fun relativeTime(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    if (delta < 0) return "just now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} hr ago"
        else -> "${minutes / (60 * 24)} days ago"
    }
}

/**
 * Guarantee the known category keys are present (server always returns
 * them, but if any are missing we render 0-byte rows rather than a gap).
 * Input is already sorted by [StorageRepositoryImpl]; this preserves order and
 * appends any missing known keys at their canonical position.
 */
private fun List<StorageCategory>.withMissingKeys(): List<StorageCategory> {
    if (isEmpty()) {
        return STORAGE_CATEGORY_ORDER.map { key ->
            StorageCategory(key = key, label = key.labelFor(), bytes = 0L)
        }
    }
    val present = associateBy { it.key }
    return STORAGE_CATEGORY_ORDER.map { key ->
        present[key] ?: StorageCategory(key = key, label = key.labelFor(), bytes = 0L)
    }
}

/** Fallback title-cased label if the server label is ever blank. */
private fun String.labelFor(): String = when (this) {
    "blobs" -> "Attachments & uploads"
    "notes" -> "Notes"
    "calendar" -> "Calendar"
    "contacts" -> "Contacts list"
    "mail" -> "Inbox mail"
    "nip46" -> "NIP-46 wraps"
    "authored" -> "Other authored events"
    "settings" -> "Settings & sync"
    else -> replaceFirstChar { it.uppercase() }
}
