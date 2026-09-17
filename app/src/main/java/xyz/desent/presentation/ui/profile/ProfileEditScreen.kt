package xyz.desent.presentation.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import xyz.desent.domain.repository.LogoutResult
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.AvatarPlaceholder
import xyz.desent.presentation.ui.components.AvatarShape
import xyz.desent.presentation.ui.components.DesentTextField
import xyz.desent.presentation.ui.components.SettingsNavCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    onNavigateBack: () -> Unit,
    /**
     * Invoked with the result of a logout once it completes, so the NavHost
     * can decide where to navigate next.
     */
    onLoggedOut: (LogoutResult) -> Unit = {},
    onNavigateToInvites: () -> Unit = {},
    onNavigateToRollKey: () -> Unit = {},
    viewModel: ProfileEditViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onPictureSelected(it) }
    }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onNavigateBack()
    }

    // React to logout completion: either navigate back to the login screen
    // (last account removed) or reset to Inbox (auto-switched to a remaining
    // account).
    LaunchedEffect(uiState.logoutResult) {
        uiState.logoutResult?.let { result ->
            viewModel.consumeLogoutResult()
            onLoggedOut(result)
        }
    }

    if (uiState.showLogoutDialog) {
        LogoutConfirmationDialog(
            onDismiss = { viewModel.dismissLogoutDialog() },
            onConfirm = { viewModel.logout() }
        )
    }

    if (uiState.showPasswordChangeDialog) {
        ChangePasswordDialog(
            isLoading = uiState.isChangingPassword,
            onDismiss = { viewModel.dismissPasswordChangeDialog() },
            onSubmit = { old, new -> viewModel.changeCustodialPassword(old, new) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save(context) },
                        enabled = !uiState.isSaving && !uiState.isLoading
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Spacer(Modifier.height(Spacing.sm))

            // Profile picture
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = { imagePicker.launch("image/*") },
                    shape = AvatarShape
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        val picModel = uiState.newPictureUri ?: uiState.pictureUrl
                        if (picModel != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(picModel).crossfade(true).build(),
                                contentDescription = "Profile picture",
                                modifier = Modifier.size(96.dp).clip(AvatarShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            AvatarPlaceholder(size = 96.dp)
                        }
                        Surface(
                            modifier = Modifier.size(28.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "Change picture",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }

            // Display Name
            DesentTextField(
                value = uiState.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                label = { Text("Display Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.sm))

            // Account actions (moved from Settings → Accounts)
            SettingsNavCard(
                title = "Invite Codes",
                subtitle = "Share your invite codes to bring friends onto DeSent.",
                onClick = onNavigateToInvites
            )

            // Key rotation (migration 040/042): self-gating entry — the wizard
            // renders its own premium lock + upsell.
            SettingsNavCard(
                title = if (uiState.tierInfo?.keyRotation == true) {
                    "Roll Your Signing Key"
                } else {
                    "Roll Your Signing Key (Premium)"
                },
                subtitle = "A brand-new Nostr key for this account — " +
                    "your address, plan, mail and data follow it.",
                onClick = onNavigateToRollKey
            )

            // Only shown when this device provisioned the account via
            // username & password (custodial) login.
            if (uiState.custodialUsername != null) {
                SettingsNavCard(
                    title = "Change Password",
                    subtitle = "Update the password for @${uiState.custodialUsername}. " +
                        "Other devices must log in again afterwards.",
                    onClick = { viewModel.showPasswordChangeDialog() }
                )
            }

            Spacer(Modifier.height(Spacing.sm))

            // Account keys + sign-out (moved from Settings → Accounts).
            AccountKeysSection(
                hexKey = uiState.hexKey,
                npubKey = uiState.npubKey,
                nsecKey = uiState.nsecKey,
                onCopy = { label, value ->
                    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                    Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                onLogout = { viewModel.showLogoutDialog() }
            )

            // Error
            uiState.error?.let { error ->
                Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun AccountKeysSection(
    hexKey: String?,
    npubKey: String?,
    nsecKey: String?,
    onCopy: (label: String, value: String) -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = "Keys & Sign-Out",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                hexKey?.let { hex ->
                    KeyDisplayRow(
                        label = "Public Key (Hex)",
                        value = hex,
                        isMasked = false,
                        onCopy = { onCopy("Hex", hex) }
                    )
                }

                npubKey?.let { npub ->
                    KeyDisplayRow(
                        label = "Public Key (Npub)",
                        value = npub,
                        isMasked = false,
                        onCopy = { onCopy("Npub", npub) }
                    )
                }

                nsecKey?.let { nsec ->
                    KeyDisplayRow(
                        label = "Private Key (Nsec)",
                        value = nsec,
                        isMasked = true,
                        onCopy = { onCopy("Nsec", nsec) }
                    )
                }

                HorizontalDivider()

                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Logout")
                }
            }
        }
    }
}

@Composable
private fun KeyDisplayRow(
    label: String,
    value: String,
    isMasked: Boolean,
    onCopy: () -> Unit
) {
    var isRevealed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isMasked && !isRevealed) {
                    "•".repeat(20)
                } else {
                    value.take(20) + "..."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )

            if (isMasked) {
                IconButton(onClick = { isRevealed = !isRevealed }) {
                    Icon(
                        imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isRevealed) "Hide" else "Show"
                    )
                }
            }

            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
            }
        }
    }
}

@Composable
private fun LogoutConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Logout") },
        text = {
            Text("Are you sure you want to logout? This will remove all your credentials and disconnect from all relays.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Logout")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Custodial password change (POST /api/custodial/credentials). Requires the
 * CURRENT password as proof-of-identity alongside key ownership — key
 * ownership alone must not allow swapping the blob.
 */
@Composable
private fun ChangePasswordDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (oldPassword: String, newPassword: String) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    val minLength = 8
    val formValid = currentPassword.isNotBlank() &&
        newPassword.length >= minLength &&
        newPassword == confirmPassword

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Change Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DesentTextField(
                    value = currentPassword,
                    onValueChange = {
                        currentPassword = it
                        validationError = null
                    },
                    label = { Text("Current password") },
                    enabled = !isLoading,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth()
                )

                DesentTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        validationError = null
                    },
                    label = { Text("New password") },
                    enabled = !isLoading,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = validationError != null,
                    supportingText = {
                        if (newPassword.isNotEmpty() && newPassword.length < minLength) {
                            Text(
                                "At least $minLength characters",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                DesentTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        validationError = null
                    },
                    label = { Text("Confirm new password") },
                    enabled = !isLoading,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth()
                )

                validationError?.let { errorMsg ->
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    text = "Your key doesn't change — this device stays logged in. " +
                        "Other devices must log in again with the new password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = formValid && !isLoading,
                onClick = {
                    when {
                        newPassword.length < minLength ->
                            validationError = "New password must be at least $minLength characters"
                        newPassword != confirmPassword ->
                            validationError = "New passwords do not match"
                        else -> onSubmit(currentPassword, newPassword)
                    }
                }
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Update")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        }
    )
}
