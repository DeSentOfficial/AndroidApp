package xyz.desent.presentation.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import kotlinx.coroutines.delay
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.shared.QRCodeImage

/**
 * State driving [CheckoutSheet] (ANDROID_PAYMENTS.md §3-4). The invoice is
 * null while a mint is in flight; [mintError] offers a manual retry for
 * transient failures (502 Strike blip, rate limit, network).
 */
data class CheckoutUiState(
    val target: xyz.desent.domain.model.PaymentTargetType,
    val targetId: Long,
    val invoice: Invoice? = null,
    val isMinting: Boolean = false,
    val mintError: String? = null,
    /** Vanity claim to auto-retry once the invoice settles server-side. */
    val claimLocalPart: String? = null,
    /** Which add-on a FEATURE_PURCHASE checkout is buying (sheet title). */
    val featureProduct: xyz.desent.domain.model.FeatureProduct? = null
)

/**
 * Lightning checkout sheet — locally rendered QR of the BOLT11 string,
 * copy-to-clipboard, optional `lightning:` wallet handoff, expiry countdown
 * and re-mint on expiry. The QR must never go through a remote image service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutSheet(
    state: CheckoutUiState,
    onReMint: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            val invoice = state.invoice
            when {
                state.isMinting -> MintingRow()
                state.mintError != null -> MintErrorRow(
                    message = state.mintError,
                    onRetry = onReMint,
                    onDismiss = onDismiss
                )
                invoice != null -> InvoiceBody(
                    invoice = invoice,
                    featureProduct = state.featureProduct,
                    onReMint = onReMint
                )
            }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

@Composable
private fun MintingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "Preparing lightning invoice…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MintErrorRow(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Text(
        text = "Couldn't create the invoice",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onDismiss) { Text("Close") }
        Spacer(Modifier.size(Spacing.sm))
        FilledTonalButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun InvoiceBody(
    invoice: Invoice,
    featureProduct: xyz.desent.domain.model.FeatureProduct?,
    onReMint: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Text(
        text = when (invoice.targetType) {
            xyz.desent.domain.model.PaymentTargetType.VANITY_REQUEST -> "Pay for your short address"
            xyz.desent.domain.model.PaymentTargetType.SLOT_PURCHASE -> "Pay for alias slots"
            xyz.desent.domain.model.PaymentTargetType.TIER_PURCHASE -> "Upgrade to the paid tier"
            xyz.desent.domain.model.PaymentTargetType.FEATURE_PURCHASE -> when (featureProduct) {
                xyz.desent.domain.model.FeatureProduct.DM_FANOUT -> "Buy the relay mirroring add-on"
                xyz.desent.domain.model.FeatureProduct.KEY_ROTATION -> "Buy the key rotation add-on"
                null -> "Buy the add-on"
            }
            null -> "Lightning payment"
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium
    )

    when (invoice.state) {
        InvoiceState.PAID -> PaidBody(invoice)
        InvoiceState.EXPIRED, InvoiceState.CANCELLED -> ExpiredBody(onReMint)
        else -> UnpaidBody(invoice, context, clipboard)
    }
}

@Composable
private fun UnpaidBody(
    invoice: Invoice,
    context: android.content.Context,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    // Sats primary; the server-derived amount_usd as context — never local math.
    Text(
        text = formatSatsWithUsd(invoice.amountSatoshi, invoice.amountUsd),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )

    ExpiryCountdown(expiresAt = invoice.expiresAt)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            QRCodeImage(
                content = invoice.lnInvoice,
                size = 240.dp,
                modifier = Modifier.padding(Spacing.sm)
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(invoice.lnInvoice))
                Toast.makeText(context, "Invoice copied", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Spacing.xs))
            Text("Copy")
        }
        FilledTonalButton(
            onClick = {
                // Optional wallet handoff; many users paste by hand instead.
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("lightning:${invoice.lnInvoice}"))
                    )
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(
                        context,
                        "No lightning wallet found — scan the QR instead",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Wallet, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Spacing.xs))
            Text("Open wallet")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.size(Spacing.sm))
        Text(
            text = "Waiting for payment…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Text(
        text = "Keep this screen open or close it — the purchase credits itself " +
            "once the payment settles, even if you leave.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PaidBody(invoice: Invoice) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.size(Spacing.sm))
        Text(
            text = if (invoice.isProcessing) "Payment received — processing" else "Payment complete",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Text(
        text = if (invoice.isProcessing) {
            "Your payment was received and is being verified. This can take a moment; " +
                "your purchase will be credited automatically — nothing else to do here."
        } else {
            "Settled — continuing automatically…"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ExpiredBody(onReMint: () -> Unit) {
    Text(
        text = "Invoice expired",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.error
    )
    Text(
        text = "The price is unchanged — generate a fresh invoice to pay with.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        FilledTonalButton(onClick = onReMint) { Text("New invoice") }
    }
}

/** Live countdown from `expires_at`; hidden when unparsable or already past. */
@Composable
private fun ExpiryCountdown(expiresAt: String?) {
    val expiryMs = remember(expiresAt) {
        try {
            expiresAt?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        } catch (e: Exception) {
            null
        }
    } ?: return

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiryMs) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val remaining = (expiryMs - nowMs).coerceAtLeast(0)
    if (remaining <= 0) return
    val minutes = remaining / 60_000
    val seconds = (remaining % 60_000) / 1_000
    val formatted = if (minutes >= 60) {
        val hours = minutes / 60
        "${hours}h ${minutes % 60}m"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
    Text(
        text = "Expires in $formatted",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
