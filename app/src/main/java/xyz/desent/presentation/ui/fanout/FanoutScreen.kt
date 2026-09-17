package xyz.desent.presentation.ui.fanout

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import xyz.desent.domain.model.BackupFetchStatus
import xyz.desent.domain.model.FanoutNipSupport
import xyz.desent.domain.model.FanoutNipSupportStatus
import xyz.desent.domain.model.FanoutRelayCheck
import xyz.desent.domain.model.FanoutRelayCheckPill
import xyz.desent.domain.model.FanoutRelayEntry
import xyz.desent.domain.model.FanoutRelayHealthPill
import xyz.desent.domain.model.FanoutRelayInfo
import xyz.desent.domain.model.RelayListMarker
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.CheckoutSheet
import xyz.desent.presentation.ui.components.avatarTintFor
import xyz.desent.presentation.ui.components.formatSatsWithUsd
import xyz.desent.presentation.ui.fanout.viewmodel.FanoutAvailability
import xyz.desent.presentation.ui.fanout.viewmodel.FanoutViewModel
import xyz.desent.presentation.ui.settings.component.SectionHeader
import xyz.desent.presentation.ui.settings.component.SwitchSetting

/**
 * Relay Mirroring settings spoke (ANDROID_DM_FANOUT.md): availability
 * gating + Strike add-on checkout, the `dm_fanout` opt-in, the NIP-65 relay
 * list editor (directory.yadha.net picker + advisory NIP highlights),
 * delivery health pills, and the backup fetch. Fails closed on tier-info.
 * Reached from the Mail spoke's Relay Mirroring card; the master toggle
 * also lives inline there via [RelayMirroringCard].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FanoutScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBilling: () -> Unit,
    viewModel: FanoutViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Full machinery load on first entry — the VM's init deliberately pulls
    // only the light toggle core so the Mail spoke can share the class.
    LaunchedEffect(Unit) {
        if (viewModel.uiState.value.isLoading) viewModel.load()
    }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relay Mirroring") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load() }) {
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
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                when (uiState.availability) {
                    FanoutAvailability.LOADING -> item {
                        AvailabilityCard(
                            title = "Checking availability…",
                            body = "Contacting desent.xyz for your plan details."
                        )
                    }

                    FanoutAvailability.NEUTRAL_LOCKED -> item {
                        // They hold the entitlement — the operator just hasn't
                        // switched the feature on. NEVER an upsell.
                        AvailabilityCard(
                            title = "Relay mirroring isn't enabled on this relay yet",
                            body = "Your plan includes relay mirroring, but the operator " +
                                "hasn't turned it on for desent.xyz. Nothing to buy. Check " +
                                "back later."
                        )
                    }

                    FanoutAvailability.UPSELL -> item {
                        UpsellCard(
                            priceSats = uiState.tierInfo?.fanoutPriceSats,
                            priceUsd = uiState.tierInfo?.fanoutPriceUsd,
                            buyEnabled = uiState.tierInfo?.fanoutPurchaseEnabled == true,
                            isBuying = uiState.isBuying,
                            onBuy = { viewModel.purchaseFanout() },
                            onSeePlans = onNavigateToBilling
                        )
                    }

                    FanoutAvailability.ENABLED -> {
                        item {
                            SwitchSetting(
                                title = "Mirror my mail to my relays",
                                description = "The relay dual-writes inbound mail and " +
                                    "delivery receipts to the relays below. Local " +
                                    "delivery stays authoritative.",
                                checked = uiState.dmFanoutEnabled,
                                onCheckedChange = { viewModel.setDmFanout(it) }
                            )
                        }
                        uiState.toggleError?.let { error ->
                            item {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        item { RelayListSection(uiState, viewModel) }
                        item { HealthSection(uiState, viewModel) }
                        item { BackupSection(uiState, viewModel) }
                        item { MirrorRepairSection(uiState, viewModel) }
                        item {
                            Text(
                                text = "Mirroring is best-effort: a dead-lettered relay loses " +
                                    "nothing. Your local copies on desent.xyz are unaffected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Poll the add-on invoice while the checkout sheet is visible.
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

    if (uiState.premiumPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPremiumPrompt() },
            title = { Text("Premium feature") },
            text = {
                Text("Relay mirroring requires an active paid plan or the mirroring add-on.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissPremiumPrompt()
                    onNavigateToBilling()
                }) { Text("See plans") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPremiumPrompt() }) { Text("Not now") }
            }
        )
    }

    uiState.directory?.let { picker ->
        DirectoryPickerSheet(
            state = picker,
            onSearch = { viewModel.searchDirectory(it.ifBlank { null }) },
            onPick = { viewModel.addFromDirectory(it) },
            onDismiss = { viewModel.closeDirectory() }
        )
    }
}

// ---------------- Availability / upsell ----------------

@Composable
private fun AvailabilityCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpsellCard(
    priceSats: Long?,
    priceUsd: String?,
    buyEnabled: Boolean,
    isBuying: Boolean,
    onBuy: () -> Unit,
    onSeePlans: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text("Mirror your mail to your own relays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Included with Yearly & Lifetime" +
                    (priceSats?.let { ", or buy it once for ${formatSatsWithUsd(it, priceUsd)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Your inbound mail and delivery receipts are dual-written to the relays " +
                    "you choose, as encrypted gift wraps the relays can't read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (buyEnabled) {
                    Button(onClick = onBuy, enabled = !isBuying) {
                        Text(if (isBuying) "Opening invoice…" else "Buy once")
                    }
                }
                OutlinedButton(onClick = onSeePlans) { Text("See plans") }
            }
        }
    }
}

// ---------------- Relay list editor ----------------

@Composable
private fun RelayListSection(
    uiState: xyz.desent.presentation.ui.fanout.viewmodel.FanoutUiState,
    viewModel: FanoutViewModel
) {
    Column {
        SectionHeader("Backup relays")
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "These relays receive a copy of your mail. " +
                "Mirroring targets writable entries (no marker or \"write\"); \"read\" " +
                "entries are excluded. Roughly the first 3 writable entries are used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))

        if (uiState.relayEntries.isEmpty()) {
            Text(
                "No relays yet. Add one manually or browse the directory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            uiState.relayEntries.forEachIndexed { index, entry ->
                RelayEntryRow(
                    entry = entry,
                    info = uiState.relayInfo[entry.url],
                    check = uiState.relayChecks[entry.url],
                    onMarker = { viewModel.setMarker(index, it) },
                    onRemove = { viewModel.removeRelay(index) }
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        var manualUrl by remember { mutableStateOf("") }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                label = { Text("wss://relay.example.com") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    viewModel.addRelay(manualUrl)
                    manualUrl = ""
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add relay")
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = { viewModel.openDirectory() }) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Browse directory")
            }
            Button(
                onClick = { viewModel.publishRelays() },
                enabled = uiState.relayListDirty && !uiState.isPublishingRelays
            ) {
                Text(if (uiState.isPublishingRelays) "Publishing…" else "Publish list")
            }
        }
    }
}

@Composable
private fun RelayEntryRow(
    entry: FanoutRelayEntry,
    info: FanoutRelayInfo?,
    check: FanoutRelayCheck?,
    onMarker: (RelayListMarker) -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RelayIcon(
                    iconUrl = info?.iconUrl,
                    seed = info?.name ?: entry.url,
                    size = 40.dp
                )
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(info?.name?.takeIf { it.isNotBlank() } ?: entry.url,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (info?.name != null) {
                        Text(
                            entry.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Advisory verdict — server relay-check when it has landed
                    // (§6.1), else the local NIP-11 assessment. NEVER blocks
                    // selection or publishing.
                    val (label, color) = if (check != null) {
                        when (check.pill) {
                            FanoutRelayCheckPill.GREEN -> "Looks good" to
                                MaterialTheme.colorScheme.primary
                            FanoutRelayCheckPill.AMBER ->
                                (check.advisoryText ?: "Capabilities unknown") to
                                    MaterialTheme.colorScheme.tertiary
                            FanoutRelayCheckPill.RED ->
                                (check.advisoryText ?: "Relay reported unusable") to
                                    MaterialTheme.colorScheme.error
                        }
                    } else {
                        when (info?.nipSupport) {
                            FanoutNipSupportStatus.SUPPORTED ->
                                "Supports mirroring" to MaterialTheme.colorScheme.primary
                            FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED ->
                                nipSupportLabel(FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED,
                                    info?.missingNips) to MaterialTheme.colorScheme.error
                            else ->
                                "NIP support unknown (no relay info doc)" to
                                    MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    }
                    Text(label, style = MaterialTheme.typography.bodySmall, color = color)
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove relay")
                }
            }
            // Probe line: directory ping + uptime (null = not probed).
            RelayPingLine(
                rttOpenMs = info?.rttOpenMs ?: check?.rttOpenMs,
                uptime7d = info?.uptime7d ?: check?.uptime7d
            )
            // One chip per checked NIP — the check list stands out here.
            RelayNipChips(supportedNips = info?.supportedNips ?: check?.supportedNips)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                RelayListMarker.entries.forEach { marker ->
                    FilterChip(
                        selected = entry.marker == marker,
                        onClick = { onMarker(marker) },
                        label = {
                            Text(
                                when (marker) {
                                    RelayListMarker.READ_WRITE -> "Read+Write"
                                    RelayListMarker.READ -> "Read"
                                    RelayListMarker.WRITE -> "Write"
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

// ---------------- Directory picker ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryPickerSheet(
    state: xyz.desent.presentation.ui.fanout.viewmodel.DirectoryPickerState,
    onSearch: (String) -> Unit,
    onPick: (FanoutRelayInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf(state.query) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg)
        ) {
            Text("Relay directory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "directory.yadha.net lists online, free, clearnet relays. Relays missing " +
                    "key DM features are highlighted, but every one stays selectable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onSearch(it)
                },
                label = { Text("Search relays") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))

            when {
                state.isLoading -> Box(
                    Modifier.fillMaxWidth().padding(Spacing.lg),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.failed -> Text(
                    "Couldn't reach the directory. Search again or add a relay manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                state.relays.isEmpty() -> Text(
                    "No relays matched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(state.relays, key = { it.url }) { relay ->
                        DirectoryRelayRow(relay = relay, onPick = { onPick(relay) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryRelayRow(relay: FanoutRelayInfo, onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (relay.nipSupport) {
                FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED ->
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RelayIcon(iconUrl = relay.iconUrl, seed = relay.name ?: relay.url, size = 40.dp)
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        relay.name ?: relay.url,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (relay.name != null) {
                        Text(
                            relay.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        nipSupportLabel(relay.nipSupport, relay.missingNips),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (relay.nipSupport == FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                TextButton(onClick = onPick) { Text("Add") }
            }
            RelayPingLine(rttOpenMs = relay.rttOpenMs, uptime7d = relay.uptime7d)
            RelayNipChips(supportedNips = relay.supportedNips)
        }
    }
}

// ---------------- Shared relay-check presentation ----------------

/**
 * One chip per checked NIP (NIP-1/9/17/59) — green when the relay
 * advertises it, error-tinted when missing. Unknown data renders a single
 * neutral chip instead of four unknowns. Purely advisory.
 */
@Composable
private fun RelayNipChips(supportedNips: Set<Int>?) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (supportedNips == null) {
            FilterChip(
                selected = false,
                onClick = {},
                label = { Text("Unknown") }
            )
            return@Row
        }
        FanoutNipSupport.CHECK_LIST.forEach { nip ->
            val supported = nip in supportedNips
            FilterChip(
                selected = supported,
                onClick = {},
                label = { Text("NIP-$nip") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = if (supported) {
                        NipChipGreen.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    },
                    labelColor = if (supported) NipChipGreen else MaterialTheme.colorScheme.error,
                    selectedContainerColor = NipChipGreen.copy(alpha = 0.22f),
                    selectedLabelColor = NipChipGreen
                )
            )
        }
    }
}

/** Directory probe line: ping chip + uptime; omitted entirely when unprobed. */
@Composable
private fun RelayPingLine(rttOpenMs: Long?, uptime7d: Double?) {
    if (rttOpenMs == null && uptime7d == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = Spacing.xs)
    ) {
        rttOpenMs?.let {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    "⚡ $it ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        uptime7d?.let {
            Text(
                "${(it * 100).toInt()}% uptime (7d)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The relay's set profile picture (NIP-11 `icon`), with a deterministic
 * tinted-initial fallback while loading/absent/failed — same pattern as
 * contact avatars.
 */
@Composable
private fun RelayIcon(iconUrl: String?, seed: String, size: androidx.compose.ui.unit.Dp) {
    val tint = remember(seed) { avatarTintFor(seed) }
    val initial = remember(seed) {
        seed.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(tint, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (iconUrl != null) {
            AsyncImage(
                model = iconUrl,
                contentDescription = "Relay icon",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                error = null // failed loads reveal the initial underneath
            )
        } else {
            Text(
                text = initial,
                color = Color.White,
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** One-line NIP verdict summary used above the chips in both surfaces. */
private fun nipSupportLabel(status: FanoutNipSupportStatus, missing: Set<Int>?): String =
    when (status) {
        FanoutNipSupportStatus.SUPPORTED -> "Supports mirroring"
        FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED ->
            if (missing.isNullOrEmpty()) {
                "Possibly not supported"
            } else {
                "Missing NIP-${missing.sorted().joinToString(", NIP-")}"
            }
        FanoutNipSupportStatus.UNKNOWN -> "Capabilities unknown"
    }

/** Green used for passing NIP chips (matches the health pill green). */
private val NipChipGreen = Color(0xFF2E7D32)

// ---------------- Health + backup fetch ----------------

@Composable
private fun HealthSection(
    uiState: xyz.desent.presentation.ui.fanout.viewmodel.FanoutUiState,
    viewModel: FanoutViewModel
) {
    Column {
        SectionHeader("Delivery health")
        Spacer(Modifier.height(Spacing.xs))
        when {
            uiState.healthFailed -> Text(
                "Couldn't load health. Tap ↻ to retry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            uiState.health == null || uiState.health.relays.isEmpty() -> Text(
                "No mirroring activity yet. Once the relay queues mail for your " +
                    "backup relays, per-relay status shows here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> uiState.health.relays.forEach { relay ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .padding(end = 2.dp)
                        ) {
                            val pillColor = when (relay.pill) {
                                FanoutRelayHealthPill.GREEN -> Color(0xFF2E7D32)
                                FanoutRelayHealthPill.AMBER -> Color(0xFFFFA000)
                                FanoutRelayHealthPill.RED -> MaterialTheme.colorScheme.error
                            }
                            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(pillColor)
                            }
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(relay.url, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${relay.done} delivered · ${relay.pending} retrying · ${relay.dead} dead",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            when (relay.pill) {
                                FanoutRelayHealthPill.GREEN -> "Delivering"
                                FanoutRelayHealthPill.AMBER -> "Retrying"
                                FanoutRelayHealthPill.RED -> "Dead-lettered"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when (relay.pill) {
                                FanoutRelayHealthPill.GREEN -> Color(0xFF2E7D32)
                                FanoutRelayHealthPill.AMBER -> Color(0xFFFFA000)
                                FanoutRelayHealthPill.RED -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupSection(
    uiState: xyz.desent.presentation.ui.fanout.viewmodel.FanoutUiState,
    viewModel: FanoutViewModel
) {    Column {
        SectionHeader("Backup inbox")
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Your mirrored mail also lives on your backup relays as ordinary " +
                "encrypted gift wraps. It's fetched automatically at every sync while " +
                "mirroring is on; anything that isn't DeSent email on those relays is ignored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        Button(
            onClick = { viewModel.fetchBackupNow() },
            enabled = !uiState.isFetchingBackup && uiState.relayEntries.isNotEmpty()
        ) {
            Text(if (uiState.isFetchingBackup) "Fetching…" else "Fetch now")
        }
        if (uiState.isFetchingBackup) {
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (uiState.backupReport.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            uiState.backupReport.forEach { result ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Checkbox(
                        checked = result.status == BackupFetchStatus.DONE,
                        onCheckedChange = null,
                        enabled = false,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(result.url, style = MaterialTheme.typography.bodySmall)
                        val line = when (result.status) {
                            BackupFetchStatus.DONE -> "Fetched"
                            BackupFetchStatus.TIMED_OUT -> "Timed out. No response"
                            BackupFetchStatus.AUTH_REQUIRED ->
                                "Requires sign-in the backup fetch can't provide"
                            BackupFetchStatus.FAILED ->
                                "Failed${result.detail?.let { ": $it" } ?: ""}"
                        }
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.status == BackupFetchStatus.DONE) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * §6.3 reconcile/import — gap detection and repair. Explicit user action
 * only (the server rate-limits reconcile to ~5/hour); `locally_deleted`
 * mail is never importable and is only reported as a count.
 */
@Composable
private fun MirrorRepairSection(
    uiState: xyz.desent.presentation.ui.fanout.viewmodel.FanoutUiState,
    viewModel: FanoutViewModel
) {
    Column {
        SectionHeader("Mirror repair")
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Check whether a mirror holds mail this device never received " +
                "(dead-lettered pushes, deletions before a sync). Missing mail is " +
                "re-fetched from desent.xyz when you import it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(
                onClick = { viewModel.reconcileMirrors() },
                enabled = !uiState.isReconciling && !uiState.isImporting
            ) {
                Text(if (uiState.isReconciling) "Checking…" else "Check for missing mail")
            }
            val report = uiState.reconcileReport
            if (report != null && report.importableIds.isNotEmpty()) {
                Button(
                    onClick = { viewModel.importMissing() },
                    enabled = !uiState.isImporting
                ) {
                    Text(if (uiState.isImporting) "Importing…" else "Import ${report.importableIds.size}")
                }
            }
        }
        if (uiState.isReconciling) {
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val report = uiState.reconcileReport
        if (report != null) {
            Spacer(Modifier.height(Spacing.sm))
            if (report.mirrors.isEmpty()) {
                Text(
                    "No mirror activity found yet. Publish a relay list and send " +
                        "yourself some mail first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                report.mirrors.forEach { mirror ->
                    Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
                        Text(mirror.url, style = MaterialTheme.typography.bodySmall)
                        val line = when {
                            mirror.error != null ->
                                "Couldn't compare: ${mirror.error}" to MaterialTheme.colorScheme.error
                            mirror.missingLocallyIds.isEmpty() ->
                                "Up to date. ${mirror.seen} mirrored, nothing missing" to
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            else ->
                                "${mirror.missingLocallyIds.size} on the mirror but missing here" +
                                    (if (mirror.missingLocallyTruncated) " (list truncated)" else "") +
                                    (if (mirror.locallyDeletedCount > 0) {
                                        " · ${mirror.locallyDeletedCount} deleted here stay deleted"
                                    } else "") to
                                    MaterialTheme.colorScheme.tertiary
                        }
                        Text(
                            line.first,
                            style = MaterialTheme.typography.bodySmall,
                            color = line.second
                        )
                    }
                }
            }
        }
    }
}
