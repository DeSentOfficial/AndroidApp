package xyz.desent.presentation.ui.login.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import xyz.desent.presentation.ui.login.viewmodel.CreateAccountMode
import xyz.desent.presentation.ui.login.viewmodel.LoginViewModel

/**
 * Overlays shared by the login-flow screens. Both are driven by the shared
 * [LoginViewModel] state, so they render on whichever screen is active when
 * the state flips: the key-backup dialog after an account is created, and the
 * one-time legacy-format hint after a v1 custodial login.
 */
@Composable
fun LoginDialogs(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    uiState.createdAccount?.let { account ->
        AccountCreatedDialog(
            account = account,
            hasAcknowledgedBackup = uiState.hasAcknowledgedBackup,
            onAcknowledgeBackup = viewModel::onAcknowledgeBackup,
            onContinue = {
                viewModel.onBackupConfirmedAndContinue()
                onLoginSuccess()
            },
            isCustodial = uiState.createAccountMode == CreateAccountMode.USERNAME_PASSWORD
        )
    }

    if (uiState.legacyAccountHint) {
        AlertDialog(
            onDismissRequest = { viewModel.onLegacyUpgradeHintDismissed() },
            title = { Text("Older encryption format") },
            text = {
                Text(
                    "You're signed in, but this account still uses the previous " +
                        "key format. Sign in once at desent.xyz with the same " +
                        "username and password to upgrade it; your key and " +
                        "address stay exactly the same."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onLegacyUpgradeHintDismissed() }) {
                    Text("Continue")
                }
            }
        )
    }
}
