package xyz.desent.presentation.ui.login.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.DesentTextField
import xyz.desent.presentation.ui.login.viewmodel.ExistingLoginMode
import xyz.desent.presentation.ui.login.viewmodel.LoginViewModel

/**
 * Fields for the currently selected existing-login method (username &
 * password vs nostr key). Pure inputs: the submit controls live in
 * [LoginMethodSubmitBar], which the screen pins below the scroll area.
 */
@Composable
fun LoginMethodFields(
    viewModel: LoginViewModel,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var passwordVisible by remember { mutableStateOf(false) }
    var custodialPasswordVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        when (uiState.existingLoginMode) {
            ExistingLoginMode.KEY ->
                if (uiState.linkedKeyProbe != null) {
                    LinkedKeyPasswordFields(
                        viewModel = viewModel,
                        uiState = uiState,
                        passwordVisible = passwordVisible,
                        onPasswordVisibilityToggle = { passwordVisible = !passwordVisible }
                    )
                } else if (uiState.ncryptsecPrompt) {
                    NcryptsecPasswordFields(
                        viewModel = viewModel,
                        uiState = uiState,
                        passwordVisible = passwordVisible,
                        onPasswordVisibilityToggle = { passwordVisible = !passwordVisible }
                    )
                } else {
                    KeyLoginFields(
                        viewModel = viewModel,
                        uiState = uiState,
                        passwordVisible = passwordVisible,
                        onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
                        onScanQr = onScanQr
                    )
                }
            ExistingLoginMode.PASSWORD -> PasswordLoginFields(
                viewModel = viewModel,
                uiState = uiState,
                passwordVisible = custodialPasswordVisible,
                onPasswordVisibilityToggle = { custodialPasswordVisible = !custodialPasswordVisible }
            )
        }
    }
}

/**
 * Submit area for the login methods: scoped error text, the 423 lockout
 * countdown, the primary CTA for the active method, and the backup-restore
 * entry point.
 */
@Composable
fun LoginMethodSubmitBar(
    viewModel: LoginViewModel,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        // 423 lockout countdown: disable submit until retry_after elapses.
        var lockRemaining by remember(uiState.accountLockedUntil) {
            mutableIntStateOf(remainingSeconds(uiState.accountLockedUntil))
        }
        LaunchedEffect(uiState.accountLockedUntil) {
            val until = uiState.accountLockedUntil ?: return@LaunchedEffect
            while (until > System.currentTimeMillis()) {
                lockRemaining = remainingSeconds(until)
                delay(1000)
            }
            lockRemaining = 0
        }

        FormErrorText(uiState.error)

        if (lockRemaining > 0) {
            Text(
                text = "Too many failed attempts, try again in ${lockRemaining}s",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
        }

        if (uiState.linkedKeyProbe != null) {
            CtaButton(
                text = "Sign In",
                enabled = viewModel.isLinkedLoginFormValid && !uiState.isLoading,
                isLoading = uiState.isLoading,
                onClick = { viewModel.onLinkedLogin() }
            )
            TextButton(
                onClick = { viewModel.cancelLinkedLogin() },
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Use a different key")
            }
        } else if (uiState.ncryptsecPrompt) {
            CtaButton(
                text = "Decrypt & Sign In",
                enabled = viewModel.isNcryptsecLoginFormValid && !uiState.isLoading,
                isLoading = uiState.isLoading,
                onClick = { viewModel.onNcryptsecLogin() }
            )
            TextButton(
                onClick = { viewModel.cancelNcryptsecLogin() },
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Use a different key")
            }
        } else {
            when (uiState.existingLoginMode) {
                ExistingLoginMode.KEY -> CtaButton(
                    text = "Continue",
                    enabled = viewModel.nsecInput.isNotBlank() && !uiState.isLoading,
                    isLoading = uiState.isLoading,
                    onClick = { viewModel.onLogin() }
                )
                ExistingLoginMode.PASSWORD -> CtaButton(
                    text = "Log In",
                    enabled = viewModel.isCustodialLoginFormValid && !uiState.isLoading && lockRemaining <= 0,
                    isLoading = uiState.isLoading,
                    onClick = { viewModel.onCustodialLogin() }
                )
            }
        }

        TextButton(
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Restore accounts from backup")
        }
    }
}

@Composable
private fun KeyLoginFields(
    viewModel: LoginViewModel,
    uiState: xyz.desent.presentation.ui.login.viewmodel.LoginUiState,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit,
    onScanQr: () -> Unit
) {
    Column {
        DesentTextField(
            value = viewModel.nsecInput,
            onValueChange = { viewModel.onNsecChange(it) },
            label = { Text("nsec or ncryptsec") },
            placeholder = { Text("nsec1…") },
            isError = uiState.error != null && viewModel.nsecInput.isNotEmpty(),
            enabled = !uiState.isLoading,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onPasswordVisibilityToggle) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide" else "Show"
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        OutlinedButton(
            onClick = onScanQr,
            enabled = !uiState.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text("Scan QR Code")
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        LoginOptionsRows(viewModel, uiState)
    }
}

@Composable
private fun PasswordLoginFields(
    viewModel: LoginViewModel,
    uiState: xyz.desent.presentation.ui.login.viewmodel.LoginUiState,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit
) {
    Column {
        DesentTextField(
            value = viewModel.custodialUsername,
            onValueChange = { viewModel.onCustodialUsernameChange(it) },
            label = { Text("Username") },
            placeholder = { Text("bravefalcon") },
            enabled = !uiState.isLoading,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        DesentTextField(
            value = viewModel.custodialPassword,
            onValueChange = { viewModel.onCustodialPasswordChange(it) },
            label = { Text("Password") },
            enabled = !uiState.isLoading,
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = onPasswordVisibilityToggle) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide" else "Show"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        LoginOptionsRows(viewModel, uiState)

        Text(
            text = "The key stored on this device is fetched once and decrypted " +
                "locally; the server never sees your password.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs)
        )
    }
}

/**
 * Linked-identity second phase (NOSTR_CUSTODIAL.md §3): the pasted key
 * proved it is a linked login identity, so the account itself opens with
 * the ACCOUNT password — the blob of the rotated (custodial) key is
 * fetched and decrypted with it, not with the old key. Submit controls live
 * in [LoginMethodSubmitBar].
 */
@Composable
private fun LinkedKeyPasswordFields(
    viewModel: LoginViewModel,
    uiState: xyz.desent.presentation.ui.login.viewmodel.LoginUiState,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit
) {
    Column {
        Text(
            text = "This key is linked to a DeSent account (kept when its keys " +
                "were rolled). Enter the account password to sign in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        DesentTextField(
            value = viewModel.linkedPassword,
            onValueChange = { viewModel.onLinkedPasswordChange(it) },
            label = { Text("Account password") },
            enabled = !uiState.isLoading,
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = onPasswordVisibilityToggle) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide" else "Show"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        LoginOptionsRows(viewModel, uiState)
    }
}

/**
 * ncryptsec second phase (NIP-49): the pasted key is password-encrypted, so
 * the password decrypts it before the ordinary key import. Decryption is
 * local-only and the password is never stored. Submit controls live in
 * [LoginMethodSubmitBar].
 */
@Composable
private fun NcryptsecPasswordFields(
    viewModel: LoginViewModel,
    uiState: xyz.desent.presentation.ui.login.viewmodel.LoginUiState,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit
) {
    Column {
        Text(
            text = "This key is password-encrypted (ncryptsec). Enter its " +
                "password to decrypt and sign in — decryption happens on this " +
                "device and the password is never stored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        DesentTextField(
            value = viewModel.ncryptsecPassword,
            onValueChange = { viewModel.onNcryptsecPasswordChange(it) },
            label = { Text("Key password") },
            enabled = !uiState.isLoading,
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = onPasswordVisibilityToggle) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide" else "Show"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        LoginOptionsRows(viewModel, uiState)
    }
}

@Composable
private fun LoginOptionsRows(viewModel: LoginViewModel, uiState: xyz.desent.presentation.ui.login.viewmodel.LoginUiState) {
    // One compact switch row (biometric nests under "remember") instead of
    // the old stacked checkbox rows.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Remember on this device", modifier = Modifier.weight(1f))
        Switch(
            checked = viewModel.rememberMe,
            onCheckedChange = { viewModel.onRememberMeChange(it) },
            enabled = !uiState.isLoading
        )
    }

    if (viewModel.rememberMe) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Unlock with biometrics",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = viewModel.enableBiometrics,
                onCheckedChange = { viewModel.onBiometricsChange(it) },
                enabled = !uiState.isLoading
            )
        }
    }
}

/** Error text scoped to the login screens (rendered above the submit CTA). */
@Composable
internal fun FormErrorText(error: String?, modifier: Modifier = Modifier) {
    error?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.sm)
        )
    }
}

internal fun remainingSeconds(retryAt: Long?): Int {
    if (retryAt == null) return 0
    val ms = retryAt - System.currentTimeMillis()
    return if (ms <= 0) 0 else ((ms + 999) / 1000).toInt()
}
