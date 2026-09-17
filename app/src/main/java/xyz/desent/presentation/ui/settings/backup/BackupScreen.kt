package xyz.desent.presentation.ui.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.settings.backup.viewmodel.BackupStep
import xyz.desent.presentation.ui.settings.backup.viewmodel.BackupUiState
import xyz.desent.presentation.ui.settings.backup.viewmodel.BackupViewModel
import xyz.desent.presentation.ui.settings.backup.viewmodel.PassphraseStrength
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import xyz.desent.presentation.ui.components.DesentTextField
import xyz.desent.presentation.ui.components.NcryptsecExportPanel
import xyz.desent.presentation.ui.components.SettingsNavCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // SAF "save to" picker. Fires encrypt + write in the VM once a Uri is chosen.
    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) viewModel.writeBackupTo(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup accounts") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                when (uiState.step) {
                    BackupStep.SelectAccounts -> SelectAccountsStep(
                        state = uiState,
                        onToggle = viewModel::toggleAccount,
                        onNext = { viewModel.nextStep() },
                    )
                    BackupStep.SetPassphrase -> SetPassphraseStep(
                        state = uiState,
                        onPassphraseChange = viewModel::onPassphraseChange,
                        onConfirmChange = viewModel::onPassphraseConfirmChange,
                        onToggleVisible = viewModel::togglePassphraseVisible,
                        onBack = viewModel::previousStep,
                        onNext = { viewModel.nextStep() },
                    )
                    BackupStep.ChooseDestination -> ChooseDestinationStep(
                        state = uiState,
                        onBack = viewModel::previousStep,
                        onPickDestination = {
                            val name = "desent-backup-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.desentbackup"
                            createDocument.launch(name)
                        },
                    )
                    BackupStep.Done -> DoneStep(onFinish = onNavigateBack)
                }
            }

            if (uiState.isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            viewModel.clearError()
        }
    }
}

@Composable
private fun SelectAccountsStep(
    state: BackupUiState,
    onToggle: (String) -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = "Select the accounts to include in the backup. Your relay list is always included.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (state.accounts.isEmpty()) {
        Text("No saved accounts found.", style = MaterialTheme.typography.bodyMedium)
    }
    state.accounts.forEach { account ->
        val selected = account.npub in state.selectedNpubs
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = selected,
                    role = Role.Switch,
                    onValueChange = { onToggle(account.npub) }
                ),
            tonalElevation = 1.dp,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = selected, onCheckedChange = { onToggle(account.npub) })
                Spacer(modifier = Modifier.width(Spacing.md))
                Column {
                    Text(
                        text = account.displayName ?: "Account",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = account.npub.take(20) + "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(Spacing.sm))
    Button(
        onClick = onNext,
        enabled = state.selectedNpubs.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Continue")
    }

    // NIP-49 ncryptsec export of the ACTIVE account's key (moved from
    // Settings → Accounts; mirrors the web /mail gear-menu "Export key…").
    state.activeNsec?.let { activeNsec ->
        var showNcryptsecExport by rememberSaveable { mutableStateOf(false) }
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
        SettingsNavCard(
            title = "Export Key (ncryptsec)",
            subtitle = "A passphrase-protected key file any Nostr app can import.",
            onClick = { showNcryptsecExport = true }
        )
        if (showNcryptsecExport) {
            AlertDialog(
                onDismissRequest = { showNcryptsecExport = false },
                title = { Text("Export encrypted key") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        NcryptsecExportPanel(nsec = activeNsec)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showNcryptsecExport = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetPassphraseStep(
    state: BackupUiState,
    onPassphraseChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onToggleVisible: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = "Choose a passphrase to encrypt the backup. This is separate from your app PIN and cannot be recovered if lost.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val strength = PassphraseStrength.estimate(state.passphrase)
    DesentTextField(
        value = state.passphrase,
        onValueChange = onPassphraseChange,
        label = { Text("Passphrase") },
        singleLine = true,
        visualTransformation = if (state.passphraseVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (state.passphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (state.passphraseVisible) "Hide passphrase" else "Show passphrase"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    if (strength != PassphraseStrength.EMPTY) {
        val warn = strength == PassphraseStrength.WEAK
        Text(
            text = "Strength: ${strength.label}" +
                if (warn) " A longer, more varied passphrase would be safer." else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    DesentTextField(
        value = state.passphraseConfirm,
        onValueChange = onConfirmChange,
        label = { Text("Confirm passphrase") },
        singleLine = true,
        isError = state.passphraseConfirm.isNotEmpty() && state.passphrase != state.passphraseConfirm,
        visualTransformation = if (state.passphraseVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    val mismatch = state.passphraseConfirm.isNotEmpty() && state.passphrase != state.passphraseConfirm
    if (mismatch) {
        Text(
            text = "Passphrases do not match.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(modifier = Modifier.height(Spacing.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
        Button(
            onClick = onNext,
            enabled = state.passphraseReady,
            modifier = Modifier.weight(1f)
        ) { Text("Continue") }
    }
}

@Composable
private fun ChooseDestinationStep(
    state: BackupUiState,
    onBack: () -> Unit,
    onPickDestination: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Default.CloudUpload,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Pick where to save your encrypted backup. You can choose local storage or a cloud provider (OneDrive, Google Drive, etc.) from the picker.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onPickDestination, modifier = Modifier.fillMaxWidth()) {
            Text("Pick save location")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        if (state.error != null) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun DoneStep(onFinish: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Backup complete",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = "Store your passphrase somewhere safe. Without it, the backup cannot be recovered.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}
