package xyz.desent.presentation.ui.agents

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.Agent
import xyz.desent.domain.model.AgentMode
import xyz.desent.presentation.ui.agents.viewmodel.AgentHandoff
import xyz.desent.presentation.ui.agents.viewmodel.AgentsViewModel
import xyz.desent.presentation.ui.components.CheckoutSheet
import xyz.desent.presentation.ui.components.DesentTextField
import xyz.desent.presentation.ui.components.TierBadge

/**
 * AI agents settings surface (ANDROID_AI_AGENTS.md). Fail-closed: the
 * section renders locked until the tier-info `agents` flag arrives true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBilling: () -> Unit = {},
    viewModel: AgentsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var editAgent by remember { mutableStateOf<Agent?>(null) }
    var deleteAgent by remember { mutableStateOf<Agent?>(null) }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { viewModel.clearError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI agents")
                        if (uiState.snapshot != null) {
                            Text(
                                text = "${uiState.capLabel} agents",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    uiState.tierInfo?.let { tier ->
                        TierBadge(tier = tier.tier, modifier = Modifier.padding(end = 8.dp))
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.sectionEnabled) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New agent") },
                    expanded = true
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
                !uiState.sectionEnabled -> LockedSection(
                    loaded = uiState.tierInfo != null,
                    onNavigateToBilling = onNavigateToBilling
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.agents.isEmpty()) {
                        item {
                            Text(
                                "No agents yet. Add an OpenClaw-style agent with its own " +
                                    "address and Nostr key. It can read (and optionally " +
                                    "answer) mail you forward to it.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(uiState.agents, key = { it.id }) { agent ->
                        AgentCard(
                            agent = agent,
                            onEdit = { editAgent = agent },
                            onDelete = { deleteAgent = agent }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateAgentDialog(
            isLoading = uiState.isCreating,
            emailDomain = uiState.snapshot?.emailDomain ?: "desent.xyz",
            onDismiss = { showCreateDialog = false },
            onCreate = { label, localPart, mode ->
                viewModel.createAgent(label, localPart, mode)
            }
        )
    }

    uiState.handoff?.let { handoff ->
        ConnectionCodeDialog(
            handoff = handoff,
            onDismiss = { viewModel.dismissHandoff() }
        )
    }

    uiState.vanityPrompt?.let { prompt ->
        VanityPromptDialog(
            prompt = prompt,
            strikeEnabled = uiState.paymentsStrikeEnabled,
            onPay = { viewModel.payVanityAddress() },
            onDismiss = { viewModel.dismissVanityPrompt() }
        )
    }

    // Lightning checkout for the short agent address (ANDROID_PAYMENTS.md §4):
    // poll while the sheet is visible, stop when it leaves composition.
    uiState.checkout?.let { checkout ->
        CheckoutSheet(
            state = checkout,
            onReMint = { viewModel.reMintInvoice() },
            onDismiss = { viewModel.dismissCheckout() }
        )
        DisposableEffect(checkout.invoice?.id) {
            viewModel.resumePolling()
            onDispose { viewModel.pausePolling() }
        }
    }

    if (uiState.capPrompt) {
        CapReachedDialog(
            onDismiss = { viewModel.dismissCapPrompt() },
            onUpgrade = {
                viewModel.dismissCapPrompt()
                onNavigateToBilling()
            }
        )
    }

    editAgent?.let { agent ->
        EditAgentDialog(
            agent = agent,
            isLoading = uiState.isSaving,
            onDismiss = { editAgent = null },
            onSave = { mode, keywords, policy, systemPrompt, displayName, pictureUrl, about ->
                viewModel.updateAgent(
                    agent.id, mode, keywords, policy, systemPrompt, displayName, pictureUrl, about
                )
                editAgent = null
            }
        )
    }

    deleteAgent?.let { agent ->
        DeleteAgentDialog(
            agent = agent,
            onDismiss = { deleteAgent = null },
            onConfirm = {
                viewModel.deleteAgent(agent.id)
                deleteAgent = null
            }
        )
    }
}

@Composable
private fun LockedSection(loaded: Boolean, onNavigateToBilling: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (loaded) "AI agents are not available" else "Checking availability…",
            style = MaterialTheme.typography.titleMedium
        )
        if (loaded) {
            Text(
                "This server has AI agents turned off for your account tier.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onNavigateToBilling) {
                Text("View plans")
            }
        }
    }
}

@Composable
private fun AgentCard(
    agent: Agent,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        agent.displayName ?: agent.label ?: agent.addressLocal,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        agent.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = onEdit,
                    label = {
                        Text(
                            when (agent.mode) {
                                AgentMode.DIGEST -> "Digest"
                                AgentMode.RESPOND -> "Respond"
                            }
                        )
                    }
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit agent")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Revoke agent",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (agent.triggerKeywords.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Triggers: ${agent.triggerKeywords.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            agent.policyNote?.takeIf { it.isNotBlank() }?.let { policy ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Policy: $policy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CreateAgentDialog(
    isLoading: Boolean,
    emailDomain: String,
    onDismiss: () -> Unit,
    onCreate: (label: String, localPart: String, mode: AgentMode) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var localPart by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AgentMode.DIGEST) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("New agent") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DesentTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (e.g. OpenClaw)") }
            )
            DesentTextField(
                value = localPart,
                onValueChange = { localPart = it },
                label = { Text("Address") }
            )
            Text(
                "$localPart@$emailDomain",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column {
                Text(
                    "Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = mode == AgentMode.DIGEST,
                        onClick = { mode = AgentMode.DIGEST },
                        label = { Text("Digest (read-only)") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = mode == AgentMode.RESPOND,
                        onClick = { mode = AgentMode.RESPOND },
                        label = { Text("Respond (may send)") }
                    )
                }
            }
            Text(
                "The agent's Nostr key is generated on this device. You'll get a " +
                    "one-time connection code to paste into the agent's skill config.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } },
        confirmButton = {
            TextButton(
                onClick = { onCreate(label, localPart, mode) },
                enabled = !isLoading && localPart.isNotBlank()
            ) {
                if (isLoading) {
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
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

/**
 * One-time handoff (§3 step 4): the ncryptsec block + passphrase are shown
 * ONCE with a copy button. After dismissal the secret is unrecoverable —
 * the user can re-export from the agent side, not from the server.
 */
@Composable
private fun ConnectionCodeDialog(handoff: AgentHandoff, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection code (shown once)") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Paste these into ${handoff.agent.label ?: "your agent"}'s skill config. " +
                    "After you close this dialog the code cannot be shown again.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "ncryptsec (connection key)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                handoff.connectionCode.ncryptsec,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 3
            )
            OutlinedButton(
                onClick = {
                    clipboard.setText(
                        androidx.compose.ui.text.AnnotatedString(
                            handoff.connectionCode.ncryptsec
                        )
                    )
                    Toast.makeText(context, "ncryptsec copied", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Copy ncryptsec")
            }
            Text(
                "Passphrase",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                handoff.connectionCode.passphrase,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            )
            OutlinedButton(
                onClick = {
                    clipboard.setText(
                        androidx.compose.ui.text.AnnotatedString(
                            handoff.connectionCode.passphrase
                        )
                    )
                    Toast.makeText(context, "Passphrase copied", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Copy passphrase")
            }
        } },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("I've saved it") }
        }
    )
}

@Composable
private fun VanityPromptDialog(
    prompt: xyz.desent.presentation.ui.agents.viewmodel.AgentVanityPrompt,
    strikeEnabled: Boolean,
    onPay: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Short address") },
        text = { Text(
            if (strikeEnabled) {
                "Addresses shorter than ${prompt.freeLength} characters carry a one-time " +
                    "price (this one: ${prompt.priceSats} sats). Continue to a lightning " +
                    "invoice. Once the payment settles, the agent is created automatically."
            } else {
                "Addresses shorter than ${prompt.freeLength} characters carry a one-time " +
                    "price (this one: ${prompt.priceSats} sats). In-app payments are " +
                    "currently unavailable on this server. Pick a longer address to " +
                    "create this agent for free."
            }
        ) },
        confirmButton = {
            if (strikeEnabled) {
                TextButton(onClick = onPay) { Text("Continue to payment") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (strikeEnabled) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun CapReachedDialog(
    onDismiss: () -> Unit,
    onUpgrade: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent limit reached") },
        text = { Text(
            "You're using all the agent slots for your tier. Paid tiers raise " +
                "the cap; lifetime is unlimited."
        ) },
        confirmButton = {
            TextButton(onClick = onUpgrade) { Text("View plans") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun EditAgentDialog(
    agent: Agent,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        mode: AgentMode,
        keywords: List<String>,
        policy: String,
        systemPrompt: String,
        displayName: String,
        pictureUrl: String,
        about: String
    ) -> Unit
) {
    var mode by remember { mutableStateOf(agent.mode) }
    var keywords by remember { mutableStateOf(agent.triggerKeywords.joinToString(", ")) }
    var policy by remember { mutableStateOf(agent.policyNote ?: "") }
    var systemPrompt by remember { mutableStateOf(agent.systemPrompt ?: "") }
    var displayName by remember { mutableStateOf(agent.displayName ?: "") }
    var pictureUrl by remember { mutableStateOf(agent.pictureUrl ?: "") }
    var about by remember { mutableStateOf(agent.about ?: "") }
    val scroll = rememberScrollState()

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Edit ${agent.displayName ?: agent.label ?: agent.address}") },
        text = { Column(
            modifier = Modifier.verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(
                    "Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = mode == AgentMode.DIGEST,
                        onClick = { mode = AgentMode.DIGEST },
                        label = { Text("Digest (read-only)") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = mode == AgentMode.RESPOND,
                        onClick = { mode = AgentMode.RESPOND },
                        label = { Text("Respond (may send)") }
                    )
                }
            }
            DesentTextField(
                value = keywords,
                onValueChange = { keywords = it },
                label = { Text("Trigger keywords (comma-separated)") }
            )
            DesentTextField(
                value = policy,
                onValueChange = { policy = it },
                label = { Text("Policy note (≤ 500 chars, stamped on every message)") }
            )
            DesentTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System prompt (the bot's persona, up to 4000 chars)") },
                modifier = Modifier.heightIn(min = 120.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Column {
                Text(
                    "Profile (NIP-01)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                DesentTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") }
                )
                DesentTextField(
                    value = pictureUrl,
                    onValueChange = { pictureUrl = it },
                    label = { Text("Picture URL (https://…)") }
                )
                DesentTextField(
                    value = about,
                    onValueChange = { about = it },
                    label = { Text("About") }
                )
            }
        } },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        mode,
                        keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        policy,
                        systemPrompt,
                        displayName,
                        pictureUrl,
                        about
                    )
                },
                enabled = !isLoading
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

@Composable
private fun DeleteAgentDialog(
    agent: Agent,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revoke agent?") },
        text = { Text(
            "${agent.address} frees immediately and the agent loses all read + " +
                "send access. Mail it already stored remains until your TTL purges it."
        ) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Revoke", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
