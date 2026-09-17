package xyz.desent.presentation.ui.settings.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.settings.backup.viewmodel.RestoreUiState
import xyz.desent.presentation.ui.settings.backup.viewmodel.RestoreViewModel
import xyz.desent.presentation.ui.components.DesentTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    onNavigateBack: () -> Unit,
    /** True when launched from the post-reinstall login flow. */
    activateFirst: Boolean = false,
    /** Optional deep-linked file Uri (opened via the .desentbackup association). */
    fileUriArg: String? = null,
    /** Invoked after a successful activate-first restore; navigates into the app. */
    onRestoredAndActive: () -> Unit = onNavigateBack,
    viewModel: RestoreViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    var pickedUri by rememberSaveable { mutableStateOf(fileUriArg) }
    var pickedFileName by rememberSaveable { mutableStateOf<String?>(null) }

    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri.toString()
            pickedFileName = uri.lastPathSegment ?: uri.toString()
        }
    }

    // Surface one-shot outcome (e.g. navigate into the app after activation).
    LaunchedEffect(uiState.outcome) {
        val outcome = uiState.outcome
        if (outcome != null) {
            if (activateFirst && outcome.activatedNpub != null) {
                viewModel.consumeOutcome()
                onRestoredAndActive()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore from backup") },
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
                val outcome = uiState.outcome
                if (outcome != null) {
                    RestoreSuccess(
                        restoredCount = outcome.restoredNpubs.size,
                        activated = outcome.activatedNpub != null,
                        onFinish = onNavigateBack,
                    )
                } else {
                    Text(
                        text = "Restore your accounts from an encrypted DeSent backup file. Enter the passphrase you set when the backup was created.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // File picker
                    OutlinedButton(
                        onClick = { openDocument.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(pickedUri?.let { "Backup file: ${pickedFileName ?: it.take(32)}" }
                            ?: "Select backup file")
                    }

                    DesentTextField(
                        value = uiState.passphrase,
                        onValueChange = viewModel::onPassphraseChange,
                        label = { Text("Passphrase") },
                        singleLine = true,
                        enabled = pickedUri != null,
                        visualTransformation = if (uiState.passphraseVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = viewModel::togglePassphraseVisible) {
                                Icon(
                                    imageVector = if (uiState.passphraseVisible) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                    contentDescription = if (uiState.passphraseVisible) "Hide passphrase" else "Show passphrase"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    uiState.error?.let { msg ->
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    Button(
                        onClick = {
                            val uri = pickedUri
                            if (uri != null) viewModel.restore(Uri.parse(uri), activateFirst)
                        },
                        enabled = pickedUri != null && uiState.passphrase.isNotEmpty() && !uiState.isWorking,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Restore") }
                }
            }

            if (uiState.isWorking) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun RestoreSuccess(
    restoredCount: Int,
    activated: Boolean,
    onFinish: () -> Unit,
) {
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
            text = "Restored $restoredCount account(s).",
            style = MaterialTheme.typography.titleLarge
        )
        if (!activated) {
            Text(
                text = "Switch to a restored account from the account switcher when you're ready.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}
