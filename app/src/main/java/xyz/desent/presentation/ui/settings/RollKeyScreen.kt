package xyz.desent.presentation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.desent.domain.usecase.KeyRotationUseCase
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.CheckoutSheet
import xyz.desent.presentation.ui.components.DesentTextField
import xyz.desent.presentation.ui.components.formatSatsWithUsd
import xyz.desent.presentation.ui.settings.viewmodel.RollKeyAvailability
import xyz.desent.presentation.ui.settings.viewmodel.RollKeyUiState
import xyz.desent.presentation.ui.settings.viewmodel.RollKeyViewModel
import xyz.desent.presentation.ui.settings.viewmodel.RollKeyWizardStep
import xyz.desent.presentation.ui.settings.viewmodel.ROLL_KEY_MIN_PASSWORD

/**
 * "Roll Your Keys" wizard (refs/FROM_email.desent.xyz/ANDROID_KEY_ROTATION.md).
 *
 * INTRO (what changes / passwords / options) → BACKUP (mandatory new-key
 * reveal + "I have saved this key" gate) → PROGRESS (snapshot → rotate →
 * local switch → restore mail → republish → cleanup) → DONE, or ERROR with
 * committed-state guidance (the account may already have moved — offer
 * cleanup retry, never a blind restart).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RollKeyScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBilling: () -> Unit,
    onRotationFinished: () -> Unit,
    viewModel: RollKeyViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Roll your signing key") },
                navigationIcon = {
                    // Once the server rotation commits there is no way back —
                    // back is only offered before the destructive step.
                    if (state.step == RollKeyWizardStep.INTRO ||
                        state.step == RollKeyWizardStep.BACKUP
                    ) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
                .padding(horizontal = Spacing.md)
        ) {
            when (state.step) {
                RollKeyWizardStep.INTRO -> IntroStep(
                    state = state,
                    viewModel = viewModel,
                    onNavigateToBilling = onNavigateToBilling,
                    onContinue = { viewModel.generateNewKey() }
                )
                RollKeyWizardStep.BACKUP -> BackupStep(
                    state = state,
                    viewModel = viewModel,
                    onRotate = { viewModel.rotate() }
                )
                RollKeyWizardStep.PROGRESS -> ProgressStep(state)
                RollKeyWizardStep.DONE -> DoneStep(
                    state = state,
                    onRetryCleanup = { viewModel.retryCleanup() },
                    onFinished = onRotationFinished
                )
                RollKeyWizardStep.ERROR -> ErrorStep(
                    state = state,
                    onRetryCleanup = { viewModel.retryCleanup() },
                    onFinished = onRotationFinished
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xl))
        }
    }

    // Poll the add-on invoice while the checkout sheet is visible.
    val checkoutInvoiceId = state.checkout?.invoice?.id
    DisposableEffect(checkoutInvoiceId) {
        if (checkoutInvoiceId != null) viewModel.resumePolling()
        onDispose { viewModel.pausePolling() }
    }

    state.checkout?.let { checkout ->
        CheckoutSheet(
            state = checkout,
            onReMint = { viewModel.reMintInvoice() },
            onDismiss = { viewModel.dismissCheckout() }
        )
    }
}

// ----------------------------------------------------------------------
// INTRO
// ----------------------------------------------------------------------

@Composable
private fun IntroStep(
    state: RollKeyUiState,
    viewModel: RollKeyViewModel,
    onNavigateToBilling: () -> Unit,
    onContinue: () -> Unit
) {
    // Fail-closed entry point (ANDROID_KEY_ROTATION.md): locked + upsell
    // while tier-info says no; the server still 402s independently.
    if (state.availability != RollKeyAvailability.ENABLED) {
        when (state.availability) {
            RollKeyAvailability.LOADING -> AvailabilityCard(
                title = "Checking availability…",
                body = "Contacting desent.xyz for your plan details."
            )
            RollKeyAvailability.NEUTRAL_LOCKED -> AvailabilityCard(
                // They hold the entitlement — the operator just hasn't
                // switched the feature on. NEVER an upsell.
                title = "Key rotation isn't enabled on this relay yet",
                body = "Your plan includes key rotation, but the operator hasn't " +
                    "turned it on for desent.xyz. Nothing to buy. Check back later."
            )
            RollKeyAvailability.UPSELL -> UpsellCard(
                priceSats = state.tierInfo?.keyRotationPriceSats,
                priceUsd = state.tierInfo?.keyRotationPriceUsd,
                buyEnabled = state.tierInfo?.keyRotationPurchaseEnabled == true,
                isBuying = state.isBuying,
                onBuy = { viewModel.purchaseKeyRotation() },
                onSeePlans = onNavigateToBilling
            )
            RollKeyAvailability.ENABLED -> Unit
        }
        return
    }

    Text(
        "Your account gets a brand-new Nostr keypair. Your address, aliases, " +
            "plan, storage, notes, contacts and calendar stay put. They move " +
            "to the new key automatically.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    Text(
        "A new password backs up the new key. Write it down: a lost password " +
            "with no saved key copy is unrecoverable by design.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(Spacing.md))

    var currentVisible by remember { mutableStateOf(false) }
    var newVisible by remember { mutableStateOf(false) }

    if (state.isCustodial) {
        DesentTextField(
            value = state.currentPassword,
            onValueChange = { viewModel.update(currentPassword = it) },
            label = { Text("Current password") },
            singleLine = true,
            visualTransformation = if (currentVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { currentVisible = !currentVisible }) {
                    Icon(
                        if (currentVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (currentVisible) "Hide" else "Show"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
    }

    DesentTextField(
        value = state.newPassword,
        onValueChange = { viewModel.update(newPassword = it) },
        label = { Text("New password") },
        singleLine = true,
        visualTransformation = if (newVisible) VisualTransformation.None
        else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { newVisible = !newVisible }) {
                Icon(
                    if (newVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (newVisible) "Hide" else "Show"
                )
            }
        },
        supportingText = { Text("At least $ROLL_KEY_MIN_PASSWORD characters") },
        isError = state.newPassword.isNotEmpty() && state.newPassword.length < ROLL_KEY_MIN_PASSWORD,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    DesentTextField(
        value = state.confirmPassword,
        onValueChange = { viewModel.update(confirmPassword = it) },
        label = { Text("Confirm new password") },
        singleLine = true,
        visualTransformation = if (newVisible) VisualTransformation.None
        else PasswordVisualTransformation(),
        isError = state.confirmPassword.isNotEmpty() && state.confirmPassword != state.newPassword,
        supportingText = {
            if (state.confirmPassword.isNotEmpty() && state.confirmPassword != state.newPassword) {
                Text("Passwords do not match")
            }
        },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(Spacing.md))

    OptionRow(
        checked = state.migrateMail,
        onCheckedChange = { viewModel.update(migrateMail = it) },
        title = "Keep my mail history",
        subtitle = "Mail is re-encrypted to the new key. Turning this off " +
            "deletes old mail (including attachment keys inside it) when the " +
            "old key is purged."
    )

    if (!state.isCustodial) {
        OptionRow(
            checked = state.keepOldIdentity,
            onCheckedChange = { viewModel.update(keepOldIdentity = it) },
            title = "Keep signing in with my current key",
            subtitle = "The old key stays linked as a sign-in method for this " +
                "account. Uncheck to retire it completely."
        )
    }

    Spacer(modifier = Modifier.height(Spacing.md))

    Button(
        onClick = onContinue,
        enabled = state.canRotate && !state.busy,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text("Generate new key")
        }
    }
}

@Composable
private fun OptionRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.padding(top = Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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
            Text("Roll your signing key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Included with Yearly & Lifetime" +
                    (priceSats?.let { ", or buy it once for ${formatSatsWithUsd(it, priceUsd)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Replace your Nostr keypair with a fresh one. Your address, " +
                    "aliases, plan and mail history move to the new key.",
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

// ----------------------------------------------------------------------
// BACKUP
// ----------------------------------------------------------------------

@Composable
private fun BackupStep(
    state: RollKeyUiState,
    viewModel: RollKeyViewModel,
    onRotate: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var nsecVisible by remember { mutableStateOf(false) }
    val nsec = state.newNsec ?: ""

    Text("Back up your new key first", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(Spacing.xs))
    Text(
        "This is the only time the raw key is shown in this flow. If you ever " +
            "lose the password and have no copy of this key, the account is " +
            "lost permanently.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(Spacing.md))

    LabeledKey(label = "New npub (public)", value = state.newNpub ?: "")
    Spacer(modifier = Modifier.height(Spacing.sm))

    Text("New nsec (secret)", style = MaterialTheme.typography.labelMedium)
    Spacer(modifier = Modifier.height(Spacing.xs))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            SelectionContainer {
                Text(
                    text = if (nsecVisible) nsec else "•".repeat(24),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(
                    onClick = { nsecVisible = !nsecVisible },
                    text = if (nsecVisible) "Hide" else "Reveal"
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(nsec)) },
                    text = "Copy"
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(Spacing.md))

    Text(
        "Prefer an encrypted copy? Create an ncryptsec from this key on the " +
            "final screen once the rotation completes (Settings → Export Key).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(Spacing.md))

    OptionRow(
        checked = state.keySaved,
        onCheckedChange = { viewModel.update(keySaved = it) },
        title = "I have saved this key somewhere safe",
        subtitle = "Required before the keys can be rolled."
    )

    Spacer(modifier = Modifier.height(Spacing.md))

    Button(
        onClick = onRotate,
        enabled = state.keySaved,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Key, contentDescription = null)
        Spacer(modifier = Modifier.size(Spacing.xs))
        Text("Roll my keys now")
    }
}

@Composable
private fun TextButton(onClick: () -> Unit, text: String) {
    OutlinedButton(onClick = onClick) { Text(text) }
}

@Composable
private fun LabeledKey(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    Text(label, style = MaterialTheme.typography.labelMedium)
    Spacer(modifier = Modifier.height(Spacing.xs))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Spacing.sm)
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
            }
        }
    }
}

// ----------------------------------------------------------------------
// PROGRESS / DONE / ERROR
// ----------------------------------------------------------------------

@Composable
private fun ProgressStep(state: RollKeyUiState) {
    val steps = KeyRotationUseCase.Step.values()
    val activeIndex = state.activeStep?.let { steps.indexOf(it) } ?: -1

    Spacer(modifier = Modifier.height(Spacing.md))
    Text("Rolling keys…", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(Spacing.xs))
    Text(
        "Keep the app open and connected. This re-encrypts your mail and " +
            "private data to the new key.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(Spacing.md))
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(Spacing.md))
    steps.forEachIndexed { index, step ->
        val label = when (step) {
            KeyRotationUseCase.Step.SNAPSHOT -> "Snapshot old-key data"
            KeyRotationUseCase.Step.ROTATE -> "Re-key the account"
            KeyRotationUseCase.Step.LOCAL_SWITCH -> "Switch this device over"
            KeyRotationUseCase.Step.RESTORE_MAIL -> "Re-encrypt mail history"
            KeyRotationUseCase.Step.REPUBLISH -> "Republish notes & calendar"
            KeyRotationUseCase.Step.CLEANUP -> "Purge the old key"
        }
        val color = when {
            index < activeIndex -> MaterialTheme.colorScheme.primary
            index == activeIndex -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = (if (index < activeIndex) "✓ " else "") + label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = color,
                fontWeight = if (index == activeIndex) FontWeight.SemiBold else FontWeight.Normal
            ),
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

@Composable
private fun DoneStep(
    state: RollKeyUiState,
    onRetryCleanup: () -> Unit,
    onFinished: () -> Unit
) {
    val result = state.result
    Spacer(modifier = Modifier.height(Spacing.md))
    Text("Keys rolled", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(Spacing.sm))
    LabeledKey(label = "New npub", value = result?.newNpub ?: state.newNpub ?: "")
    Spacer(modifier = Modifier.height(Spacing.sm))
    Text(
        buildString {
            if (result != null) {
                append("${result.wrapsRestored} mail item(s) re-encrypted")
                if (result.wrapsDropped > 0) {
                    append(", ${result.wrapsDropped} system notice(s) dropped")
                }
                append(".\n")
                append("${result.eventsRepublished} private-data item(s) republished")
                if (result.eventsFailed > 0) append(" (${result.eventsFailed} failed)")
                append(".")
            }
        },
        style = MaterialTheme.typography.bodyMedium
    )
    if (result?.identityLinked == true) {
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            "Your previous key remains linked as a sign-in method.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (result?.cleanupPending == true) {
        Spacer(modifier = Modifier.height(Spacing.sm))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text(
                    "Old-key data still on the server",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    "The final purge did not complete. Retry it. Until then, " +
                        "anyone holding the old key can still read old mail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Button(onClick = onRetryCleanup, enabled = !state.busy) { Text("Retry purge") }
            }
        }
    } else {
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            "Pairings from the old key are now dead. " +
            "Re-pair any bunker or external app you used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(Spacing.md))
    Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
        Text("Continue to my account")
    }
}

@Composable
private fun ErrorStep(
    state: RollKeyUiState,
    onRetryCleanup: () -> Unit,
    onFinished: () -> Unit
) {
    Spacer(modifier = Modifier.height(Spacing.md))
    Text(
        if (state.committed) "Rotation partially completed" else "Rotation failed",
        style = MaterialTheme.typography.titleMedium,
        color = if (state.committed) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    Text(
        state.error ?: "Unknown error",
        style = MaterialTheme.typography.bodyMedium
    )
    if (state.committed) {
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            "The account has already moved to the new key. The address and " +
                "sign-in work with the new password. Don't start over.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        if (state.result?.cleanupPending == true || state.result == null) {
            Button(onClick = onRetryCleanup, enabled = !state.busy) { Text("Retry final purge") }
            Spacer(modifier = Modifier.height(Spacing.sm))
        }
        Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to my account")
        }
    } else {
        Spacer(modifier = Modifier.height(Spacing.md))
        OutlinedButton(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
            Text("Close")
        }
    }
}
