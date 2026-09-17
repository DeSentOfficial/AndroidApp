package xyz.desent.presentation.ui.alias.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.alias.viewmodel.VanityPricePrompt
import xyz.desent.presentation.ui.components.formatSatsWithUsd

/**
 * "Request this address" sheet — the 402 `vanity_price` route (short-name
 * pricing, NOT the alias count cap). The server quote from the 402 body is
 * authoritative. With [strikeEnabled] the request is paid via a lightning
 * invoice and approved automatically; otherwise payment is settled
 * out-of-band by the operator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VanityRequestSheet(
    prompt: VanityPricePrompt,
    emailDomain: String,
    strikeEnabled: Boolean,
    isSubmitting: Boolean,
    onRequest: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = "Short address",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${prompt.localPart}@$emailDomain is ${prompt.localPart.length} characters — " +
                    "shorter names carry a one-time price.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${formatSatsWithUsd(prompt.priceSats, prompt.priceUsd)} (one-time)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (strikeEnabled) {
                    "Continue to a lightning invoice. Once the payment settles, " +
                        "the address is approved automatically and claimed for you."
                } else {
                    "Payment is settled with the operator. Request the address and, " +
                        "once approved, come back here to claim it — the approval is consumed " +
                        "by the successful claim."
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
                TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Not now") }
                Spacer(Modifier.size(Spacing.sm))
                FilledTonalButton(
                    onClick = onRequest,
                    enabled = !isSubmitting
                ) {
                    Text(
                        when {
                            isSubmitting -> "Requesting…"
                            strikeEnabled -> "Continue to payment"
                            else -> "Request this address"
                        }
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}
