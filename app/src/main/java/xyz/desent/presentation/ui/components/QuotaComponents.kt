package xyz.desent.presentation.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.desent.presentation.theme.Spacing

/** Human-readable byte size, e.g. "1.4 MB", "2.05 GB". */
fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        bytes < 1024L -> "$bytes B"
        kb < 1024.0 -> String.format("%.1f KB", kb)
        mb < 1024.0 -> String.format("%.1f MB", mb)
        else -> String.format("%.2f GB", gb)
    }
}

/** Locale-grouped satoshi amount, e.g. "50,000". */
fun formatSats(sats: Long): String = java.text.NumberFormat.getNumberInstance().format(sats)

/** Exact USD sticker rendering, e.g. "$50.00"; null/blank in → null out. */
fun formatUsd(sticker: String?): String? =
    sticker?.takeIf { it.isNotBlank() }?.let { "$$it" }

/** Sats primary, dollar secondary (ANDROID_PAYMENTS.md §2); no local conversion. */
fun formatSatsWithUsd(sats: Long, usdSticker: String?): String =
    if (usdSticker.isNullOrBlank()) "${formatSats(sats)} sats"
    else "${formatSats(sats)} sats (≈ ${formatUsd(usdSticker)})"

/**
 * "MMM d, yyyy" rendering of a `paid_until` ISO timestamp for renewal copy;
 * returns null for null/blank/unparseable input (never invents a date).
 */
fun formatPaidUntil(iso: String?): String? =
    iso?.takeIf { it.isNotBlank() }?.let {
        runCatching {
            java.time.Instant.parse(it).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
        }.getOrNull()
    }

/** 402 `slot_price` payload + live slot math driving [SlotPurchaseSheet]. */
data class SlotPurchasePrompt(
    val tier: String?,
    val freeCap: Int?,
    val slotsOwned: Int?,
    val cap: Int?,
    val used: Int?,
    val priceSats: Long,
    val priceUsd: String? = null
)

@Composable
fun TierBadge(tier: String, modifier: Modifier = Modifier) {
    val isLifetime = tier.equals("lifetime", ignoreCase = true)
    val isPaid = isLifetime || tier.equals("paid", ignoreCase = true)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (isPaid) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp
    ) {
        Text(
            text = when {
                isLifetime -> "Lifetime"
                isPaid -> "Paid"
                else -> "Free"
            },
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (isPaid) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Storage usage progress bar. Renders nothing when there's no cap to compare
 * against (e.g. the server omitted storage fields).
 */
@Composable
fun StorageUsageBar(used: Long?, cap: Long?, modifier: Modifier = Modifier) {
    if (cap == null || cap <= 0) return
    val safeUsed = used ?: 0L
    val fraction = (safeUsed.toFloat() / cap).coerceIn(0f, 1f)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Storage",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = "${formatBytes(safeUsed)} / ${formatBytes(cap)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Informational upgrade nudge for free-tier users (no in-app payment). */
@Composable
fun UpgradeBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Free tier",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Upgrade for 5 GiB storage and 25 aliases.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
