package xyz.desent.presentation.ui.billing

import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.HistoryPill
import xyz.desent.domain.model.PaymentsConfig
import xyz.desent.domain.model.PurchaseHistoryItem
import xyz.desent.domain.model.PurchasePlan
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.billing.viewmodel.BillingViewModel
import xyz.desent.presentation.ui.components.CheckoutSheet
import xyz.desent.presentation.ui.components.TierBadge
import xyz.desent.presentation.ui.components.formatBytes
import xyz.desent.presentation.ui.components.formatPaidUntil
import xyz.desent.presentation.ui.components.formatSatsWithUsd
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pricing & Billing (ANDROID_PAYMENTS.md): current plan with renewal,
 * yearly/lifetime plan cards paid via the shared lightning checkout, and
 * the unified purchase timeline. Sats primary / dollar secondary; every
 * amount comes from server responses — the sticker is decoration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(
    onNavigateBack: () -> Unit,
    viewModel: BillingViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pricing & Billing") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                item {
                    CurrentPlanCard(uiState.tierInfo)
                }
                item { PlanSectionHeader("Available plans") }
                item {
                    YearlyPlanCard(
                        config = uiState.paymentsConfig,
                        buyable = uiState.tierSalesOpen,
                        tier = uiState.tierInfo,
                        isBuying = uiState.isBuyingTier,
                        onBuy = { viewModel.purchaseTier(PurchasePlan.YEARLY) }
                    )
                }
                if (uiState.paymentsConfig.lifetimeEnabled) {
                    item {
                        LifetimePlanCard(
                            config = uiState.paymentsConfig,
                            buyable = uiState.tierSalesOpen,
                            tier = uiState.tierInfo,
                            isBuying = uiState.isBuyingTier,
                            onBuy = { viewModel.purchaseTier(PurchasePlan.LIFETIME) }
                        )
                    }
                }
                if (!uiState.paymentsConfig.strikeEnabled) {
                    item {
                        Text(
                            text = "In-app payments are currently disabled — purchases are reviewed by the operator.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item { PlanSectionHeader("Add-ons") }
                item {
                    AddonsCard(
                        tier = uiState.tierInfo,
                        strikeEnabled = uiState.paymentsConfig.strikeEnabled,
                        isBuyingFeature = uiState.isBuyingFeature,
                        onBuy = { viewModel.purchaseFeature(it) }
                    )
                }
                item { PlanSectionHeader("Purchase history") }
                if (uiState.historyFailed) {
                    item {
                        Text(
                            text = "Couldn't load purchase history. Tap ↻ to retry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else if (uiState.history.isEmpty()) {
                    item {
                        Text(
                            text = "No purchases yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(uiState.history, key = { "${it.kind}-${it.targetId}-${it.requestedAt}" }) { entry ->
                        HistoryRow(entry)
                    }
                }
            }
        }
    }

    // Poll the plan invoice while the checkout sheet is visible (§4).
    val checkoutInvoiceId = uiState.checkout?.invoice?.id
    DisposableEffect(checkoutInvoiceId) {
        if (checkoutInvoiceId != null) viewModel.resumePolling()
        onDispose { viewModel.pausePolling() }
    }

    uiState.checkout?.let { checkout ->
        CheckoutSheet(
            state = checkout,
            onReMint = { viewModel.reMintInvoice() },
            onDismiss = { viewModel.dismissCheckout() }
        )
    }
}

@Composable
private fun PlanSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.sm)
    )
}

@Composable
private fun CurrentPlanCard(tier: AliasTierInfo?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Current plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                tier?.let { TierBadge(tier = it.tier) }
            }
            if (tier == null) {
                Text(
                    "Loading plan…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(Spacing.sm))
                val aliasLine = if (tier.cap != null) "${tier.used} / ${tier.cap} aliases"
                else "${tier.used} aliases (unlimited)"
                Text(aliasLine, style = MaterialTheme.typography.bodyMedium)
                if (tier.storageUsed != null && tier.storageCap != null) {
                    Text(
                        "${formatBytes(tier.storageUsed)} of ${formatBytes(tier.storageCap)} storage",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                formatPaidUntil(tier.paidUntil)?.let {
                    Text(
                        "Renews $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (tier.isPaid && !tier.isLifetime) {
                    Text(
                        "Buying a year again extends your renewal date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun YearlyPlanCard(
    config: PaymentsConfig,
    buyable: Boolean,
    tier: AliasTierInfo?,
    isBuying: Boolean,
    onBuy: () -> Unit
) {
    val isPaidYearly = tier?.isPaid == true && !tier.isLifetime
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text("Paid · yearly", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = config.tierPriceSats?.let { formatSatsWithUsd(it, config.tierPriceUsd) }
                    ?: (config.tierPriceUsd?.let { "≈ $$it" } ?: "Price unavailable"),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "5 aliases · 2.5 GiB storage · premium features",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            if (buyable) {
                Button(onClick = onBuy, enabled = !isBuying) {
                    Text(
                        when {
                            isBuying -> "Opening invoice…"
                            isPaidYearly -> "Extend one year"
                            else -> "Subscribe yearly"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LifetimePlanCard(
    config: PaymentsConfig,
    buyable: Boolean,
    tier: AliasTierInfo?,
    isBuying: Boolean,
    onBuy: () -> Unit
) {
    // Never offer lifetime to an account that already has it (§6).
    if (tier?.isLifetime == true) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text("Lifetime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = config.lifetimePriceSats?.let { formatSatsWithUsd(it, config.lifetimePriceUsd) }
                    ?: (config.lifetimePriceUsd?.let { "≈ $$it" } ?: "Price unavailable"),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "Unlimited aliases · 25 GiB storage · one-time payment",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            if (buyable) {
                OutlinedButton(onClick = onBuy, enabled = !isBuying) {
                    Text(if (isBuying) "Opening invoice…" else "Upgrade to lifetime")
                }
            }
        }
    }
}

/**
 * One-off add-ons (§3.1b): buy buttons only while the per-product sales
 * switch is on; owned/included rows never render one. Prices always come
 * from tier-info — never hardcoded, never converted locally.
 */
@Composable
private fun AddonsCard(
    tier: AliasTierInfo?,
    strikeEnabled: Boolean,
    isBuyingFeature: FeatureProduct?,
    onBuy: (FeatureProduct) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            AddonRow(
                title = "Relay mirroring",
                description = "Dual-write inbound mail to your own relays",
                priceSats = tier?.fanoutPriceSats,
                priceUsd = tier?.fanoutPriceUsd,
                owned = tier?.isPaid == true || tier?.dmFanoutPurchased == true,
                ownedLabel = if (tier?.isPaid == true) "Included with your plan" else "Owned",
                buyable = strikeEnabled && tier?.fanoutPurchaseEnabled == true,
                isBuying = isBuyingFeature == FeatureProduct.DM_FANOUT,
                onBuy = { onBuy(FeatureProduct.DM_FANOUT) }
            )
            Spacer(Modifier.height(Spacing.md))
            AddonRow(
                title = "Key rotation",
                description = "Roll your signing key without losing your account",
                priceSats = tier?.keyRotationPriceSats,
                priceUsd = tier?.keyRotationPriceUsd,
                owned = tier?.isPaid == true || tier?.keyRotationPurchased == true,
                ownedLabel = if (tier?.isPaid == true) "Included with your plan" else "Owned",
                buyable = strikeEnabled && tier?.keyRotationPurchaseEnabled == true,
                isBuying = isBuyingFeature == FeatureProduct.KEY_ROTATION,
                onBuy = { onBuy(FeatureProduct.KEY_ROTATION) }
            )
        }
    }
}

@Composable
private fun AddonRow(
    title: String,
    description: String,
    priceSats: Long?,
    priceUsd: String?,
    owned: Boolean,
    ownedLabel: String,
    buyable: Boolean,
    isBuying: Boolean,
    onBuy: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!owned) {
                Text(
                    text = priceSats?.let { formatSatsWithUsd(it, priceUsd) } ?: "One-time purchase",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        when {
            owned -> Text(
                ownedLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            buyable -> Button(onClick = onBuy, enabled = !isBuying) {
                Text(if (isBuying) "Opening…" else "Buy once")
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: PurchaseHistoryItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.kindLabel, style = MaterialTheme.typography.titleSmall)
                val amount = formatSatsWithUsd(entry.amountSatoshi, entry.amountUsd)
                Text(
                    text = amount,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                entry.requestedAt?.let { requested ->
                    Text(
                        text = formatDate(requested),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                entry.note?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "Note: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.decidedBy?.equals("strike", ignoreCase = true) != true && entry.decidedBy != null) {
                    Text(
                        text = "Reviewed by operator",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = entry.pill.label,
                style = MaterialTheme.typography.labelMedium,
                color = entry.pill.color(),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private val HistoryPill.label: String
    get() = when (this) {
        HistoryPill.CREDITED -> "Credited"
        HistoryPill.PROCESSING -> "Paid · processing"
        HistoryPill.AWAITING -> "Awaiting payment"
        HistoryPill.DENIED -> "Denied"
        HistoryPill.MUTED -> "Pending"
    }

@Composable
private fun HistoryPill.color() = when (this) {
    HistoryPill.CREDITED -> MaterialTheme.colorScheme.primary
    HistoryPill.PROCESSING -> androidx.compose.ui.graphics.Color(0xFFFFA000)
    HistoryPill.AWAITING -> MaterialTheme.colorScheme.tertiary
    HistoryPill.DENIED -> MaterialTheme.colorScheme.error
    HistoryPill.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatDate(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
}.getOrDefault(iso)
