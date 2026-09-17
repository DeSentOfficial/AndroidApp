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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.desent.presentation.theme.Spacing

/**
 * Alias slot-pack purchase sheet — the 402 `slot_price` route (alias count
 * cap, NOT name length). With [strikeEnabled] the pack is paid via a
 * lightning invoice and the cap rises on settle; otherwise payment is
 * settled out-of-band by the operator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotPurchaseSheet(
    prompt: SlotPurchasePrompt,
    strikeEnabled: Boolean,
    isSubmitting: Boolean,
    onBuy: (quantity: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var quantity by remember { mutableIntStateOf(1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = "Buy more alias slots",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            val used = prompt.used
            val cap = prompt.cap
            if (used != null && cap != null) {
                Text(
                    text = "You're using $used of $cap aliases." +
                        (prompt.freeCap?.let { " Your tier allotment is $it" } ?: "") +
                        (prompt.slotsOwned?.takeIf { it > 0 }?.let { " + $it bought slots." } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Quantity", style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (quantity > 1) quantity-- }, enabled = quantity > 1) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease quantity")
                    }
                    Text(
                        text = "$quantity",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = Spacing.sm)
                    )
                    IconButton(onClick = { if (quantity < MAX_SLOT_PACK) quantity++ }, enabled = quantity < MAX_SLOT_PACK) {
                        Icon(Icons.Default.Add, contentDescription = "Increase quantity")
                    }
                }
            }

            if (prompt.priceSats > 0) {
                Text(
                    text = formatSatsWithUsd(prompt.priceSats, prompt.priceUsd) + " per slot",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Total: ${formatSats(prompt.priceSats * quantity)} sats (one-time)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = if (strikeEnabled) {
                    "Slots are permanent — deleting an alias never un-buys one. " +
                        "Continue to a lightning invoice; the cap rises as soon as the payment settles."
                } else {
                    "Slots are permanent — deleting an alias never un-buys one. " +
                        "Payment is settled with the operator; the cap rises as soon as the purchase is approved."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Spacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") }
                Spacer(Modifier.size(Spacing.sm))
                FilledTonalButton(
                    onClick = { onBuy(quantity) },
                    enabled = !isSubmitting
                ) {
                    Text(
                        when {
                            isSubmitting -> "Requesting…"
                            strikeEnabled -> "Continue to payment"
                            else -> "Request $quantity slot${if (quantity > 1) "s" else ""}"
                        }
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

private const val MAX_SLOT_PACK = 25
