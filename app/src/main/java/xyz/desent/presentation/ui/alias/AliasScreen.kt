package xyz.desent.presentation.ui.alias

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import xyz.desent.domain.model.Alias
import xyz.desent.domain.model.VanityConfig
import xyz.desent.domain.model.VanityRequest
import xyz.desent.domain.model.VanityRequestStatus
import xyz.desent.presentation.ui.alias.components.VanityRequestSheet
import xyz.desent.presentation.ui.alias.viewmodel.AliasViewModel
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.CheckoutSheet
import xyz.desent.presentation.ui.components.SlotPurchaseSheet
import xyz.desent.presentation.ui.components.TierBadge
import xyz.desent.presentation.ui.components.formatBytes
import xyz.desent.presentation.ui.components.formatSatsWithUsd
import xyz.desent.presentation.ui.components.DesentTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliasScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBilling: () -> Unit = {},
    viewModel: AliasViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var sheetAlias by remember { mutableStateOf<Alias?>(null) }
    var deleteAlias by remember { mutableStateOf<Alias?>(null) }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { viewModel.clearError() }
    }

    val tier = uiState.tierInfo
    // Informational only — the cap is soft; slot packs raise it server-side.
    // Lifetime (null cap) is never at cap.
    val atCap = tier?.isAtCap == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Aliases")
                        if (tier != null) {
                            val storage = if (tier.storageUsed != null && tier.storageCap != null)
                                " · ${formatBytes(tier.storageUsed)} / ${formatBytes(tier.storageCap)}"
                            else ""
                            val aliasText = if (tier.cap != null) {
                                "${tier.used} of ${tier.cap} aliases"
                            } else {
                                "${tier.used} aliases (unlimited)"
                            }
                            Text(
                                text = "$aliasText$storage",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (tier != null) {
                        TierBadge(
                            tier = tier.tier,
                            modifier = Modifier.padding(end = Spacing.sm)
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New alias") },
                expanded = true
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.error!!,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(onClick = { viewModel.refresh() }) {
                            Text("Retry")
                        }
                    }
                }

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (atCap && tier != null) {
                            SlotCapBanner(
                                used = tier.used,
                                cap = tier.cap,
                                onBuySlots = { viewModel.openSlotPurchase() },
                                modifier = Modifier.padding(horizontal = Spacing.md)
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }

                        if (uiState.showTierCta) {
                            TierUpgradeCard(
                                priceSats = uiState.paymentsConfig.tierPriceSats,
                                priceUsd = uiState.paymentsConfig.tierPriceUsd,
                                isBuying = uiState.isBuyingTier,
                                onUpgrade = { viewModel.purchaseTier() },
                                modifier = Modifier.padding(horizontal = Spacing.md)
                            )
                            TextButton(
                                onClick = onNavigateToBilling,
                                modifier = Modifier.padding(horizontal = Spacing.md)
                            ) {
                                Text("All plans & purchase history ›")
                            }
                            Spacer(Modifier.height(Spacing.sm))
                        }

                        if (uiState.vanityRequests.isNotEmpty()) {
                            VanityRequestsSection(
                                requests = uiState.vanityRequests,
                                onClaim = { viewModel.startClaimFromRequest(it) }
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }

                        if (uiState.aliases.isEmpty() &&
                            uiState.vanityRequests.isEmpty() &&
                            !atCap
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.AlternateEmail,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(Modifier.height(Spacing.sm))
                                    Text(
                                        text = "No aliases yet",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Tap \"New alias\" to create one",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(vertical = Spacing.md),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(uiState.aliases, key = { it.id }) { alias ->
                                    AliasListItem(
                                        alias = alias,
                                        onClick = { sheetAlias = alias },
                                        onLongClick = { sheetAlias = alias }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(uiState.prefillLocalPart) {
        if (uiState.prefillLocalPart != null) showCreateDialog = true
    }

    if (showCreateDialog) {
        CreateAliasDialog(
            emailDomain = uiState.emailDomain,
            initialLocalPart = uiState.prefillLocalPart ?: "",
            vanityConfig = uiState.vanityConfig,
            strikeEnabled = uiState.paymentsConfig.strikeEnabled,
            isCreating = uiState.isCreating,
            onDismiss = {
                showCreateDialog = false
                viewModel.onPrefillConsumed()
            },
            onCreate = { localPart, label ->
                viewModel.createAlias(localPart, label)
                showCreateDialog = false
            }
        )
    }

    uiState.vanityPricePrompt?.let { prompt ->
        VanityRequestSheet(
            prompt = prompt,
            emailDomain = uiState.emailDomain,
            strikeEnabled = uiState.paymentsConfig.strikeEnabled,
            isSubmitting = uiState.isRequestingVanity,
            onRequest = { viewModel.requestVanityAddress(prompt.localPart, uiState.emailDomain) },
            onDismiss = { viewModel.dismissVanityPrompt() }
        )
    }

    uiState.slotPricePrompt?.let { prompt ->
        SlotPurchaseSheet(
            prompt = prompt,
            strikeEnabled = uiState.paymentsConfig.strikeEnabled,
            isSubmitting = uiState.isBuyingSlots,
            onBuy = { quantity -> viewModel.buySlots(quantity) },
            onDismiss = { viewModel.dismissSlotPrompt() }
        )
    }

    // Poll the invoice while the checkout sheet is visible; stop when it isn't
    // (ANDROID_PAYMENTS.md §4). Re-keyed on re-mint so the loop follows the
    // fresh invoice id.
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

    sheetAlias?.let { alias ->
        AliasActionSheet(
            alias = alias,
            onDismiss = { sheetAlias = null },
            onDelete = {
                sheetAlias = null
                deleteAlias = alias
            }
        )
    }

    deleteAlias?.let { alias ->
        AlertDialog(
            onDismissRequest = { deleteAlias = null },
            title = { Text("Delete alias?") },
            text = {
                Text("Delete ${alias.email}? This is irreversible. Mail already delivered stays in your inbox, but future mail to this alias will be rejected.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAlias(alias.id)
                        deleteAlias = null
                    },
                    enabled = !uiState.isDeleting
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAlias = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateAliasDialog(
    emailDomain: String,
    initialLocalPart: String,
    vanityConfig: VanityConfig?,
    strikeEnabled: Boolean,
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (localPart: String, label: String?) -> Unit
) {
    var localPart by remember(initialLocalPart) { mutableStateOf(initialLocalPart) }
    var label by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val priceSats = vanityConfig?.priceOf(localPart)
    val priceUsd = vanityConfig?.usdStickerOf(localPart)

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = { Text("New alias") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DesentTextField(
                    value = localPart,
                    onValueChange = { localPart = it.filter { ch -> ch.isLetterOrDigit() || ch in "._-" } },
                    label = { Text("Local part") },
                    singleLine = true,
                    prefix = { Text("@", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = { Text("@$emailDomain", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Letters, digits, '.', '_', '-'. Max 50 chars. Server lowercases it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (priceSats != null) {
                    Text(
                        text = "${formatSatsWithUsd(priceSats, priceUsd)} one-time — " +
                            if (strikeEnabled) "paid in-app via lightning" else "short names need operator approval",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                DesentTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(localPart, label) },
                enabled = !isCreating && localPart.isNotBlank()
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) { Text("Cancel") }
        }
    )
}

/** Soft-cap notice: the create flow stays open; slots are bought, not gated. */
@Composable
private fun SlotCapBanner(
    used: Int,
    cap: Int?,
    onBuySlots: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Alias cap reached",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = if (cap != null) {
                        "You're using $used of $cap aliases. Slot packs permanently raise the cap."
                    } else {
                        "You're using $used aliases with no cap. Slot packs are still available."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            FilledTonalButton(onClick = onBuySlots) {
                Text("Buy slots")
            }
        }
    }
}

/**
 * Paid-tier upgrade card — shown only when Strike payments are enabled and
 * the account is on the free tier (ANDROID_PAYMENTS.md §6). Lifetime grant:
 * 25 aliases, 5 GiB storage, premium features.
 */
@Composable
private fun TierUpgradeCard(
    priceSats: Long?,
    priceUsd: String?,
    isBuying: Boolean,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Upgrade to the paid tier",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "25 aliases, 5 GiB storage and premium features — lifetime, one payment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (priceSats != null && priceSats > 0) {
                    Text(
                        text = formatSatsWithUsd(priceSats, priceUsd),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            FilledTonalButton(onClick = onUpgrade, enabled = !isBuying) {
                if (isBuying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Upgrade")
                }
            }
        }
    }
}

/** Status list for the caller's vanity (short address) approval requests. */
@Composable
private fun VanityRequestsSection(
    requests: List<VanityRequest>,
    onClaim: (VanityRequest) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Address requests",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        requests.forEach { request ->
            VanityRequestRow(request = request, onClaim = onClaim)
        }
    }
}

@Composable
private fun VanityRequestRow(
    request: VanityRequest,
    onClaim: (VanityRequest) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = request.email,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    VanityStatusBadge(status = request.status)
                }
                if (!request.note.isNullOrBlank()) {
                    Text(
                        text = request.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (request.status == VanityRequestStatus.APPROVED) {
                    Text(
                        text = "Approved — finish claiming it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (request.status == VanityRequestStatus.APPROVED) {
                TextButton(onClick = { onClaim(request) }) {
                    Text("Claim")
                }
            }
        }
    }
}

@Composable
private fun VanityStatusBadge(status: VanityRequestStatus) {
    val (label, color) = when (status) {
        VanityRequestStatus.PENDING -> "pending" to MaterialTheme.colorScheme.onSurfaceVariant
        VanityRequestStatus.APPROVED -> "approved" to MaterialTheme.colorScheme.primary
        VanityRequestStatus.DENIED -> "denied" to MaterialTheme.colorScheme.error
        VanityRequestStatus.CLAIMED -> "claimed" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AliasListItem(
    alias: Alias,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        color = if (alias.isActive) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AlternateEmail,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alias.email,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!alias.label.isNullOrBlank()) {
                    Text(
                        text = alias.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!alias.isActive) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.outline
                ) {
                    Text(
                        text = "inactive",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AliasActionSheet(
    alias: Alias,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Text(
                text = alias.email,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            if (!alias.label.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = alias.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            DetailRow("Status", if (alias.isActive) "Active" else "Inactive")
            DetailRow("Created", alias.createdAt)
            DetailRow("Local part", alias.localPart)
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDelete)
                    .padding(vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Delete alias",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
