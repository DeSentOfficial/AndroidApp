package xyz.desent.presentation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.usecase.SecurityConfigUseCase
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.DesentTextField
import xyz.desent.presentation.ui.settings.component.SecurityAlertsSection
import xyz.desent.presentation.ui.settings.component.SettingsCard
import xyz.desent.presentation.ui.settings.component.SettingsSubScreen
import xyz.desent.presentation.ui.settings.component.SwitchSetting
import xyz.desent.presentation.ui.settings.viewmodel.SettingsViewModel

/**
 * Security & privacy spoke: app lock (PIN / biometrics / re-lock) plus the
 * login-security alert preference, moved here from the old single-page
 * Settings layout.
 */
@Composable
fun PrivacySettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel,
    securityConfigUseCase: SecurityConfigUseCase,
    preferencesManager: PreferencesManager
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.showPinDialog) {
        PinDialog(
            onDismiss = { viewModel.dismissPinDialog() },
            onPinEntered = { pin -> viewModel.onPinEntered(pin) }
        )
    }

    SettingsSubScreen(title = "Security & privacy", onNavigateBack = onNavigateBack) {
        SettingsCard(
            icon = Icons.Default.Lock,
            title = "App lock",
            subtitle = "Unlock DeSent with your PIN or biometrics"
        ) {
            AppLockContent(
                requirePinOnOpen = uiState.requirePinOnOpen,
                requireBiometricOnOpen = uiState.requireBiometricOnOpen,
                requireBiometricOnSigning = uiState.requireBiometricOnSigning,
                relockSeconds = uiState.appLockRelockSeconds,
                onRequirePinOnOpenChange = { viewModel.setRequirePinOnOpen(it) },
                onRequireBiometricOnOpenChange = { viewModel.setRequireBiometricOnOpen(it) },
                onRequireBiometricOnSigningChange = { viewModel.setRequireBiometricOnSigning(it) },
                onRelockSecondsChange = { viewModel.setAppLockRelockSeconds(it) }
            )
        }

        SettingsCard(
            icon = Icons.Default.Security,
            title = "Security alerts",
            subtitle = "Saved to your desent.xyz account"
        ) {
            SecurityAlertsSection(
                securityConfigUseCase = securityConfigUseCase,
                preferencesManager = preferencesManager,
                showHeader = false
            )
        }
    }
}

/** The app-lock rows: toggles plus the re-lock timing chips (when armed). */
@Composable
private fun AppLockContent(
    requirePinOnOpen: Boolean,
    requireBiometricOnOpen: Boolean,
    requireBiometricOnSigning: Boolean,
    relockSeconds: Long,
    onRequirePinOnOpenChange: (Boolean) -> Unit,
    onRequireBiometricOnOpenChange: (Boolean) -> Unit,
    onRequireBiometricOnSigningChange: (Boolean) -> Unit,
    onRelockSecondsChange: (Long) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SwitchSetting(
            title = "Require PIN on App Open",
            description = "Enter 4-digit PIN to unlock app",
            checked = requirePinOnOpen,
            onCheckedChange = onRequirePinOnOpenChange
        )
        SwitchSetting(
            title = "Biometric on App Open",
            description = "Use fingerprint or face to unlock",
            checked = requireBiometricOnOpen,
            onCheckedChange = onRequireBiometricOnOpenChange
        )
        SwitchSetting(
            title = "Biometric on Event Signing",
            description = "Authenticate before signing events",
            checked = requireBiometricOnSigning,
            onCheckedChange = onRequireBiometricOnSigningChange
        )

        if (requirePinOnOpen || requireBiometricOnOpen) {
            HorizontalDivider()

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(text = "Re-lock", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "When to require unlocking again after leaving the app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    val options = listOf(0L to "Immediately", 60L to "1 min", 300L to "5 min")
                    options.forEach { (secs, label) ->
                        FilterChip(
                            selected = relockSeconds == secs,
                            onClick = { onRelockSecondsChange(secs) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    }
}

/** Set-PIN dialog, moved unchanged from the old Settings screen. */
@Composable
private fun PinDialog(
    onDismiss: () -> Unit,
    onPinEntered: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set PIN") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                DesentTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                            pin = it
                            error = null
                        }
                    },
                    label = { Text("Enter 4-digit PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                DesentTextField(
                    value = confirmPin,
                    onValueChange = {
                        if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                            confirmPin = it
                            error = null
                        }
                    },
                    label = { Text("Confirm PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null
                )

                error?.let { errorMsg ->
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        pin.length != 4 -> error = "PIN must be 4 digits"
                        pin != confirmPin -> error = "PINs do not match"
                        else -> onPinEntered(pin)
                    }
                }
            ) {
                Text("Set PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
